package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.javarosa.core.model.FormDef;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReportDefinitionService {

    public static final String KIND_ANSWER = "ANSWER";
    public static final String KIND_SYSTEM = "SYSTEM";
    private static final Set<String> AGGREGATIONS = Set.of("NONE", "COUNT", "SUM", "AVG", "MIN", "MAX");
    private static final Set<String> OPERATORS = Set.of(
            "EQ", "NE", "CONTAINS", "GT", "GTE", "LT", "LTE", "IN", "BETWEEN", "IS_EMPTY", "NOT_EMPTY");
    private static final Set<String> REPORT_TYPES = Set.of("TABLE", "SUMMARY");
    private static final Set<String> WIDGET_TYPES = Set.of("TABLE", "NUMBER", "BAR", "LINE", "PIE");

    private final JdbcTemplate jdbc;
    private final JavaRosaFormLoader formLoader;
    private final FormMetadataExtractor metadataExtractor;
    private final AuditService auditService;

    public List<DefinitionView> listDefinitions(UUID projectId) {
        ensureProjectExists(projectId);
        return jdbc.query("""
                SELECT id, project_id, form_id, name, description, report_type, is_active, created_at, updated_at
                FROM report_definitions
                WHERE project_id = ?
                ORDER BY name ASC
                """, (rs, rowNum) -> definitionRow(rs, false), projectId);
    }

    public DefinitionView getDefinition(UUID projectId, UUID reportId) {
        List<DefinitionView> definitions = jdbc.query("""
                SELECT id, project_id, form_id, name, description, report_type, is_active, created_at, updated_at
                FROM report_definitions
                WHERE id = ? AND project_id = ?
                """, (rs, rowNum) -> definitionRow(rs, true), reportId, projectId);
        if (definitions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Report definition not found");
        }
        return definitions.get(0);
    }

    @Transactional
    public DefinitionView createDefinition(UUID projectId, DefinitionCommand command) {
        validateDefinitionCommand(projectId, command);
        UUID id = UUID.randomUUID();
        UUID actorId = currentUserId().orElse(null);
        try {
            jdbc.update("""
                    INSERT INTO report_definitions
                        (id, project_id, form_id, name, description, report_type, is_active, created_by_id, updated_by_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, projectId, command.formId(), command.name().trim(), blankToNull(command.description()),
                    normalizedReportType(command.reportType()), command.active() == null || command.active(), actorId, actorId);
            replaceFields(id, command.fields());
            replaceFilters(id, command.filters());
            DefinitionView created = getDefinition(projectId, id);
            auditService.log(AuditActions.REPORT_CREATED, "REPORT", id, created.name(), Map.of(
                    "projectId", projectId.toString(),
                    "formId", created.formId().toString()));
            return created;
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A report with this name already exists in the project", ex);
        }
    }

    @Transactional
    public DefinitionView updateDefinition(UUID projectId, UUID reportId, DefinitionCommand command) {
        getDefinition(projectId, reportId);
        validateDefinitionCommand(projectId, command);
        UUID actorId = currentUserId().orElse(null);
        try {
            jdbc.update("""
                    UPDATE report_definitions
                    SET form_id = ?, name = ?, description = ?, report_type = ?, is_active = ?,
                        updated_by_id = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND project_id = ?
                    """, command.formId(), command.name().trim(), blankToNull(command.description()),
                    normalizedReportType(command.reportType()), command.active() == null || command.active(),
                    actorId, reportId, projectId);
            replaceFields(reportId, command.fields());
            replaceFilters(reportId, command.filters());
            DefinitionView updated = getDefinition(projectId, reportId);
            auditService.log(AuditActions.REPORT_UPDATED, "REPORT", reportId, updated.name(), Map.of(
                    "projectId", projectId.toString(),
                    "formId", updated.formId().toString()));
            return updated;
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A report with this name already exists in the project", ex);
        }
    }

    @Transactional
    public void deleteDefinition(UUID projectId, UUID reportId) {
        DefinitionView existing = getDefinition(projectId, reportId);
        int deleted = jdbc.update("DELETE FROM report_definitions WHERE id = ? AND project_id = ?", reportId, projectId);
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Report definition not found");
        }
        auditService.log(AuditActions.REPORT_DELETED, "REPORT", reportId, existing.name(), Map.of(
                "projectId", projectId.toString(),
                "formId", existing.formId().toString()));
    }

    public List<AvailableField> discoverFields(UUID projectId, UUID formId) {
        ensureFormBelongsToProject(projectId, formId);
        LinkedHashMap<String, AvailableField> fields = new LinkedHashMap<>();
        for (AvailableField system : systemFields()) {
            fields.put(fieldKey(system.fieldKind(), system.fieldPath()), system);
        }

        List<String> xml = jdbc.query("""
                SELECT xml_content
                FROM form_versions
                WHERE form_id = ?
                ORDER BY is_latest DESC, created_at DESC
                LIMIT 1
                """, (rs, rowNum) -> rs.getString(1), formId);
        if (!xml.isEmpty() && xml.get(0) != null && !xml.get(0).isBlank()) {
            try {
                FormDef formDef = formLoader.loadFormDef(xml.get(0));
                FormMetadataExtractor.FormMetadata metadata = metadataExtractor.extractMetadata(formDef);
                for (FormMetadataExtractor.FieldInfo field : metadata.getFields()) {
                    if (field == null || field.getXpath() == null || field.getXpath().isBlank()) continue;
                    String path = canonicalFormPath(field.getXpath());
                    if (path.isBlank()) continue;
                    String label = firstNonBlank(field.getLabel(), field.getName(), path);
                    AvailableField available = new AvailableField(path, label, KIND_ANSWER,
                            firstNonBlank(field.getType(), "string"), field.isMedia(), field.isCalculated());
                    fields.putIfAbsent(fieldKey(KIND_ANSWER, path), available);
                }
            } catch (RuntimeException ignored) {
                // Stored submission-answer discovery below remains a safe fallback for older/partially compatible forms.
            }
        }

        jdbc.query("""
                SELECT DISTINCT a.path, a.field_name, a.field_type
                FROM submission_answers a
                JOIN submissions s ON s.id = a.submission_id
                WHERE s.form_id = ?
                ORDER BY a.path ASC
                """, rs -> {
            String path = canonicalAnswerPath(rs.getString("path"));
            if (path.isBlank()) return;
            AvailableField available = new AvailableField(path,
                    firstNonBlank(rs.getString("field_name"), path), KIND_ANSWER,
                    firstNonBlank(rs.getString("field_type"), "string"), false, false);
            fields.putIfAbsent(fieldKey(KIND_ANSWER, path), available);
        }, formId);

        return new ArrayList<>(fields.values());
    }

    public List<WidgetView> listWidgets(UUID projectId) {
        ensureProjectExists(projectId);
        return jdbc.query("""
                SELECT id, project_id, report_definition_id, title, widget_type, config, sort_order, is_active,
                       created_at, updated_at
                FROM dashboard_widgets
                WHERE project_id = ?
                ORDER BY sort_order ASC, title ASC
                """, (rs, rowNum) -> new WidgetView(
                uuid(rs, "id"), uuid(rs, "project_id"), uuidNullable(rs, "report_definition_id"),
                rs.getString("title"), rs.getString("widget_type"), rs.getString("config"),
                rs.getInt("sort_order"), rs.getBoolean("is_active"), instant(rs, "created_at"), instant(rs, "updated_at")),
                projectId);
    }

    @Transactional
    public WidgetView createWidget(UUID projectId, WidgetCommand command) {
        ensureProjectExists(projectId);
        if (command == null || command.title() == null || command.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Widget title is required");
        }
        String type = firstNonBlank(command.widgetType(), "TABLE").toUpperCase(Locale.ROOT);
        if (!WIDGET_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported widget type: " + type);
        }
        if (command.reportDefinitionId() != null) getDefinition(projectId, command.reportDefinitionId());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO dashboard_widgets
                    (id, project_id, report_definition_id, title, widget_type, config, sort_order, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, projectId, command.reportDefinitionId(), command.title().trim(), type,
                blankToNull(command.config()), command.sortOrder() == null ? 0 : command.sortOrder(),
                command.active() == null || command.active());
        return listWidgets(projectId).stream().filter(w -> w.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteWidget(UUID projectId, UUID widgetId) {
        int deleted = jdbc.update("DELETE FROM dashboard_widgets WHERE id = ? AND project_id = ?", widgetId, projectId);
        if (deleted == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dashboard widget not found");
    }

    private DefinitionView definitionRow(ResultSet rs, boolean includeChildren) throws SQLException {
        UUID id = uuid(rs, "id");
        List<FieldView> fields = includeChildren ? fields(id) : List.of();
        List<FilterView> filters = includeChildren ? filters(id) : List.of();
        return new DefinitionView(id, uuid(rs, "project_id"), uuid(rs, "form_id"), rs.getString("name"),
                rs.getString("description"), rs.getString("report_type"), rs.getBoolean("is_active"),
                fields, filters, instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    public List<FieldView> fields(UUID reportId) {
        return jdbc.query("""
                SELECT id, field_path, field_kind, label, data_type, aggregation, group_by, sort_order
                FROM report_fields WHERE report_definition_id = ?
                ORDER BY sort_order ASC, id ASC
                """, (rs, rowNum) -> new FieldView(uuid(rs, "id"), rs.getString("field_path"),
                rs.getString("field_kind"), rs.getString("label"), rs.getString("data_type"),
                rs.getString("aggregation"), rs.getBoolean("group_by"), rs.getInt("sort_order")), reportId);
    }

    public List<FilterView> filters(UUID reportId) {
        return jdbc.query("""
                SELECT id, field_path, field_kind, operator, value_text, value_to, sort_order
                FROM report_filters WHERE report_definition_id = ?
                ORDER BY sort_order ASC, id ASC
                """, (rs, rowNum) -> new FilterView(uuid(rs, "id"), rs.getString("field_path"),
                rs.getString("field_kind"), rs.getString("operator"), rs.getString("value_text"),
                rs.getString("value_to"), rs.getInt("sort_order")), reportId);
    }

    private void replaceFields(UUID reportId, List<FieldCommand> commands) {
        jdbc.update("DELETE FROM report_fields WHERE report_definition_id = ?", reportId);
        if (commands == null) return;
        int order = 0;
        for (FieldCommand command : commands) {
            if (command == null || command.fieldPath() == null || command.fieldPath().isBlank()) continue;
            String kind = normalizedKind(command.fieldKind());
            String aggregation = firstNonBlank(command.aggregation(), "NONE").toUpperCase(Locale.ROOT);
            if (!AGGREGATIONS.contains(aggregation)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported aggregation: " + aggregation);
            }
            if (!KIND_SYSTEM.equals(kind) && command.fieldPath().startsWith("$submission.")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "System fields must use fieldKind SYSTEM");
            }
            jdbc.update("""
                    INSERT INTO report_fields
                        (id, report_definition_id, field_path, field_kind, label, data_type, aggregation, group_by, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), reportId, command.fieldPath().trim(), kind,
                    blankToNull(command.label()), blankToNull(command.dataType()), aggregation,
                    command.groupBy() != null && command.groupBy(), command.sortOrder() == null ? order : command.sortOrder());
            order++;
        }
    }

    private void replaceFilters(UUID reportId, List<FilterCommand> commands) {
        jdbc.update("DELETE FROM report_filters WHERE report_definition_id = ?", reportId);
        if (commands == null) return;
        int order = 0;
        for (FilterCommand command : commands) {
            if (command == null || command.fieldPath() == null || command.fieldPath().isBlank()) continue;
            String operator = firstNonBlank(command.operator(), "EQ").toUpperCase(Locale.ROOT);
            if (!OPERATORS.contains(operator)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported filter operator: " + operator);
            }
            jdbc.update("""
                    INSERT INTO report_filters
                        (id, report_definition_id, field_path, field_kind, operator, value_text, value_to, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), reportId, command.fieldPath().trim(), normalizedKind(command.fieldKind()),
                    operator, blankToNull(command.value()), blankToNull(command.valueTo()),
                    command.sortOrder() == null ? order : command.sortOrder());
            order++;
        }
    }

    private void validateDefinitionCommand(UUID projectId, DefinitionCommand command) {
        ensureProjectExists(projectId);
        if (command == null || command.formId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source form is required");
        }
        ensureFormBelongsToProject(projectId, command.formId());
        if (command.name() == null || command.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Report name is required");
        }
        normalizedReportType(command.reportType());
    }

    private String normalizedReportType(String reportType) {
        String type = firstNonBlank(reportType, "TABLE").toUpperCase(Locale.ROOT);
        if (!REPORT_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported report type: " + type);
        }
        return type;
    }

    private static String normalizedKind(String kind) {
        String normalized = firstNonBlank(kind, KIND_ANSWER).toUpperCase(Locale.ROOT);
        if (!KIND_ANSWER.equals(normalized) && !KIND_SYSTEM.equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported field kind: " + normalized);
        }
        return normalized;
    }

    private void ensureProjectExists(UUID projectId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM projects WHERE id = ?", Integer.class, projectId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
    }

    private void ensureFormBelongsToProject(UUID projectId, UUID formId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM xforms WHERE id = ? AND project_id = ?",
                Integer.class, formId, projectId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source form does not belong to this project");
        }
    }

    private Optional<UUID> currentUserId() {
        try {
            var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || authentication.getName() == null || "anonymousUser".equals(authentication.getName())) {
                return Optional.empty();
            }
            List<UUID> ids = jdbc.query("SELECT id FROM users WHERE username = ?", (rs, rowNum) -> uuid(rs, "id"),
                    authentication.getName());
            return ids.stream().findFirst();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    public static List<AvailableField> systemFields() {
        return List.of(
                new AvailableField("$submission.id", "Submission ID", KIND_SYSTEM, "uuid", false, false),
                new AvailableField("$submission.instance_id", "Instance ID", KIND_SYSTEM, "string", false, false),
                new AvailableField("$submission.status", "Submission Status", KIND_SYSTEM, "string", false, false),
                new AvailableField("$submission.submitted_at", "Submitted At", KIND_SYSTEM, "dateTime", false, false),
                new AvailableField("$submission.form_version", "Form Version", KIND_SYSTEM, "string", false, false),
                new AvailableField("$submission.submitter", "Submitter", KIND_SYSTEM, "string", false, false),
                new AvailableField("$submission.source", "Source Client", KIND_SYSTEM, "string", false, false)
        );
    }

    public static String canonicalFormPath(String xpath) {
        if (xpath == null) return "";
        String path = xpath.trim();
        while (path.startsWith("/")) path = path.substring(1);
        int slash = path.indexOf('/');
        if (slash >= 0) path = path.substring(slash + 1);
        return canonicalAnswerPath(path);
    }

    public static String canonicalAnswerPath(String path) {
        if (path == null) return "";
        String normalized = path.trim();
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        normalized = normalized.replaceAll("\\[\\d+\\]", "");
        normalized = normalized.replaceAll("/{2,}", "/");
        return normalized;
    }

    public static String fieldKey(String kind, String path) {
        return normalizedKind(kind) + ":" + (path == null ? "" : path);
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
    }

    private static UUID uuidNullable(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record DefinitionCommand(String name, String description, UUID formId, String reportType, Boolean active,
                                    List<FieldCommand> fields, List<FilterCommand> filters) {}
    public record FieldCommand(String fieldPath, String fieldKind, String label, String dataType,
                               String aggregation, Boolean groupBy, Integer sortOrder) {}
    public record FilterCommand(String fieldPath, String fieldKind, String operator, String value,
                                String valueTo, Integer sortOrder) {}
    public record FieldView(UUID id, String fieldPath, String fieldKind, String label, String dataType,
                            String aggregation, boolean groupBy, int sortOrder) {}
    public record FilterView(UUID id, String fieldPath, String fieldKind, String operator, String value,
                             String valueTo, int sortOrder) {}
    public record DefinitionView(UUID id, UUID projectId, UUID formId, String name, String description,
                                 String reportType, boolean active, List<FieldView> fields, List<FilterView> filters,
                                 Instant createdAt, Instant updatedAt) {}
    public record AvailableField(String fieldPath, String label, String fieldKind, String dataType,
                                 boolean media, boolean calculated) {}
    public record WidgetCommand(UUID reportDefinitionId, String title, String widgetType, String config,
                                Integer sortOrder, Boolean active) {}
    public record WidgetView(UUID id, UUID projectId, UUID reportDefinitionId, String title, String widgetType,
                             String config, int sortOrder, boolean active, Instant createdAt, Instant updatedAt) {}
}
