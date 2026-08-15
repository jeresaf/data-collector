package org.odk.platform.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only, filterable audit trail query service.
 *
 * User-supplied values are always bound parameters. The only dynamic SQL is a
 * fixed set of optional predicates controlled by this service, not arbitrary
 * column names or expressions supplied by clients.
 */
@Service
@RequiredArgsConstructor
public class AuditTrailQueryService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final NamedParameterJdbcTemplate jdbc;

    public AuditPage search(AuditFilter requested) {
        AuditFilter filter = requested == null ? new AuditFilter(null, null, null, null, null, null,
                null, null, 0, DEFAULT_PAGE_SIZE) : requested.normalized();

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        addEquals(where, params, "al.action", "action", filter.action());
        addEquals(where, params, "al.resource_type", "resourceType", filter.resourceType());
        addEquals(where, params, "al.resource_id", "resourceId", filter.resourceId());
        addEquals(where, params, "al.status", "status", filter.status());
        addEquals(where, params, "al.ip_address", "ipAddress", filter.ipAddress());

        if (notBlank(filter.actor())) {
            where.append(" AND LOWER(COALESCE(u.username, au.username, '')) LIKE :actor ");
            params.addValue("actor", "%" + filter.actor().trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (filter.from() != null) {
            where.append(" AND al.created_at >= :from ");
            params.addValue("from", Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" AND al.created_at <= :to ");
            params.addValue("to", Timestamp.from(filter.to()));
        }

        String joins = """
                 FROM audit_logs al
                 LEFT JOIN users u ON u.id = al.user_id
                 LEFT JOIN app_users au ON au.id = al.app_user_id
                """;

        Long totalValue = jdbc.queryForObject("SELECT COUNT(*) " + joins + where, params, Long.class);
        long total = totalValue == null ? 0L : totalValue;

        params.addValue("limit", filter.size());
        params.addValue("offset", (long) filter.page() * filter.size());
        List<AuditEntry> entries = jdbc.query("""
                SELECT al.id, al.action, al.resource_type, al.resource_id, al.resource_name,
                       al.old_values, al.new_values, al.ip_address, al.user_agent,
                       al.request_id, al.session_id, al.status, al.error_message, al.metadata,
                       al.user_id, al.app_user_id, al.created_at,
                       u.username AS user_username, au.username AS app_user_username
                """ + joins + where + " ORDER BY al.created_at DESC, al.id DESC LIMIT :limit OFFSET :offset",
                params, (rs, rowNum) -> entry(rs, List.of()));

        if (!entries.isEmpty()) {
            List<UUID> ids = entries.stream().map(AuditEntry::id).toList();
            MapSqlParameterSource detailParams = new MapSqlParameterSource("auditIds", ids);
            Map<UUID, List<AuditDetail>> detailMap = new LinkedHashMap<>();
            jdbc.query("""
                    SELECT id, audit_log_id, detail_key, old_value, new_value, value_type, metadata, created_at
                    FROM audit_log_details
                    WHERE audit_log_id IN (:auditIds)
                    ORDER BY created_at ASC, detail_key ASC, id ASC
                    """, detailParams, rs -> {
                UUID auditId = uuid(rs, "audit_log_id");
                detailMap.computeIfAbsent(auditId, ignored -> new ArrayList<>()).add(detail(rs));
            });
            entries = entries.stream()
                    .map(entry -> entry.withDetails(detailMap.getOrDefault(entry.id(), List.of())))
                    .toList();
        }

        int totalPages = filter.size() == 0 ? 0 : (int) Math.ceil((double) total / filter.size());
        return new AuditPage(entries, filter.page(), filter.size(), total, totalPages);
    }

    private static void addEquals(StringBuilder where, MapSqlParameterSource params,
                                  String column, String parameter, String value) {
        if (!notBlank(value)) return;
        where.append(" AND ").append(column).append(" = :").append(parameter).append(' ');
        params.addValue(parameter, value.trim());
    }

    private static AuditEntry entry(ResultSet rs, List<AuditDetail> details) throws SQLException {
        Object userId = rs.getObject("user_id");
        Object appUserId = rs.getObject("app_user_id");
        String webUsername = rs.getString("user_username");
        String appUsername = rs.getString("app_user_username");
        String actorType;
        String actorId;
        String actorName;
        if (userId != null) {
            actorType = "USER";
            actorId = String.valueOf(userId);
            actorName = webUsername;
        } else if (appUserId != null) {
            actorType = "APP_USER";
            actorId = String.valueOf(appUserId);
            actorName = appUsername;
        } else {
            actorType = "SYSTEM";
            actorId = null;
            actorName = "system";
        }
        Timestamp created = rs.getTimestamp("created_at");
        return new AuditEntry(
                uuid(rs, "id"),
                rs.getString("action"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("resource_name"),
                actorType,
                actorId,
                actorName,
                rs.getString("ip_address"),
                rs.getString("user_agent"),
                rs.getString("request_id"),
                rs.getString("session_id"),
                rs.getString("status"),
                rs.getString("error_message"),
                rs.getString("old_values"),
                rs.getString("new_values"),
                rs.getString("metadata"),
                created == null ? null : created.toInstant(),
                details == null ? List.of() : details
        );
    }

    private static AuditDetail detail(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new AuditDetail(
                uuid(rs, "id"),
                rs.getString("detail_key"),
                rs.getString("old_value"),
                rs.getString("new_value"),
                rs.getString("value_type"),
                rs.getString("metadata"),
                created == null ? null : created.toInstant()
        );
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public record AuditFilter(String action, String resourceType, String resourceId, String status,
                              String actor, String ipAddress, Instant from, Instant to, Integer page, Integer size) {
        public AuditFilter normalized() {
            int safePage = page == null || page < 0 ? 0 : page;
            int requestedSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : size;
            int safeSize = Math.min(requestedSize, MAX_PAGE_SIZE);
            return new AuditFilter(blank(action), blank(resourceType), blank(resourceId), blank(status),
                    blank(actor), blank(ipAddress), from, to, safePage, safeSize);
        }

        private static String blank(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    public record AuditPage(List<AuditEntry> items, int page, int size, long totalElements, int totalPages) {}

    public record AuditEntry(UUID id, String action, String resourceType, String resourceId, String resourceName,
                             String actorType, String actorId, String actorName, String ipAddress, String userAgent,
                             String requestId, String sessionId, String status, String errorMessage,
                             String oldValues, String newValues, String metadata, Instant createdAt,
                             List<AuditDetail> details) {
        AuditEntry withDetails(List<AuditDetail> newDetails) {
            return new AuditEntry(id, action, resourceType, resourceId, resourceName, actorType, actorId, actorName,
                    ipAddress, userAgent, requestId, sessionId, status, errorMessage, oldValues, newValues, metadata,
                    createdAt, newDetails == null ? Collections.emptyList() : List.copyOf(newDetails));
        }
    }

    public record AuditDetail(UUID id, String key, String oldValue, String newValue,
                              String valueType, String metadata, Instant createdAt) {}
}
