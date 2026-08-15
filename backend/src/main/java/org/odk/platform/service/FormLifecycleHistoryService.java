package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormLifecycleHistoryService {

    private final JdbcTemplate jdbcTemplate;

    public List<FormVersionView> listVersions(UUID projectId, UUID formId) {
        String sql = """
                SELECT
                    fv.id,
                    fv.version,
                    fv.status,
                    fv.is_published,
                    fv.is_latest,
                    fv.changelog,
                    fv.published_at,
                    fv.validated_at,
                    fv.deprecated_at,
                    fv.archived_at,
                    fv.created_at,
                    fv.updated_at
                FROM form_versions fv
                JOIN xforms f ON f.id = fv.form_id
                WHERE f.project_id = ?
                  AND f.id = ?
                ORDER BY fv.created_at DESC, fv.version DESC
                """;

        return jdbcTemplate.query(sql, this::mapVersion, projectId, formId);
    }

    public List<LifecycleHistoryView> listHistory(UUID projectId, UUID formId) {
        String sql = """
                SELECT
                    h.id,
                    h.form_version_id,
                    fv.version,
                    h.action,
                    h.previous_version_id,
                    pv.version AS previous_version,
                    h.performed_by,
                    u.username,
                    u.first_name,
                    u.last_name,
                    h.performed_at,
                    h.notes
                FROM form_publish_history h
                JOIN form_versions fv ON fv.id = h.form_version_id
                JOIN xforms f ON f.id = fv.form_id
                JOIN users u ON u.id = h.performed_by
                LEFT JOIN form_versions pv ON pv.id = h.previous_version_id
                WHERE f.project_id = ?
                  AND f.id = ?
                ORDER BY h.performed_at DESC, h.id DESC
                """;

        return jdbcTemplate.query(sql, this::mapHistory, projectId, formId);
    }

    private FormVersionView mapVersion(ResultSet rs, int rowNum) throws SQLException {
        return new FormVersionView(
                rs.getObject("id", UUID.class),
                rs.getString("version"),
                rs.getString("status"),
                rs.getBoolean("is_published"),
                rs.getBoolean("is_latest"),
                rs.getString("changelog"),
                instant(rs, "published_at"),
                instant(rs, "validated_at"),
                instant(rs, "deprecated_at"),
                instant(rs, "archived_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private LifecycleHistoryView mapHistory(ResultSet rs, int rowNum) throws SQLException {
        String username = rs.getString("username");
        String firstName = rs.getString("first_name");
        String lastName = rs.getString("last_name");
        String performedByName = fullName(firstName, lastName, username);
        return new LifecycleHistoryView(
                rs.getObject("id", UUID.class),
                rs.getObject("form_version_id", UUID.class),
                rs.getString("version"),
                rs.getString("action"),
                rs.getObject("previous_version_id", UUID.class),
                rs.getString("previous_version"),
                rs.getObject("performed_by", UUID.class),
                username,
                performedByName,
                instant(rs, "performed_at"),
                rs.getString("notes"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String fullName(String firstName, String lastName, String fallback) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        String combined = (first + " " + last).trim();
        return combined.isEmpty() ? fallback : combined;
    }

    public record FormVersionView(
            UUID id,
            String version,
            String status,
            boolean isPublished,
            boolean isLatest,
            String changelog,
            Instant publishedAt,
            Instant validatedAt,
            Instant deprecatedAt,
            Instant archivedAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record LifecycleHistoryView(
            UUID id,
            UUID formVersionId,
            String version,
            String action,
            UUID previousVersionId,
            String previousVersion,
            UUID performedById,
            String performedByUsername,
            String performedByName,
            Instant performedAt,
            String notes) {
    }
}
