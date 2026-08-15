package org.odk.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportRunService {

    private final JdbcTemplate jdbc;
    private final ReportDefinitionService definitions;
    private final ObjectMapper objectMapper;

    public ReportResult run(UUID projectId, UUID reportId, RunParameters parameters) {
        ReportDefinitionService.DefinitionView definition = definitions.getDefinition(projectId, reportId);
        RunParameters effectiveParameters = parameters == null ? new RunParameters(null, null, null, null, null, List.of()) : parameters;
        UUID runId = UUID.randomUUID();
        Instant started = Instant.now();
        UUID actorId = currentUserId().orElse(null);
        jdbc.update("""
                INSERT INTO report_runs
                    (id, report_definition_id, run_by_id, parameters, row_count, source_submission_count, status, started_at)
                VALUES (?, ?, ?, ?, 0, 0, 'RUNNING', ?)
                """, runId, reportId, actorId, toJson(effectiveParameters), Timestamp.from(started));

        try {
            List<SubmissionRow> submissions = loadSubmissions(definition.formId(), effectiveParameters);
            loadAnswers(submissions);

            List<ReportDefinitionService.FilterView> savedFilters = definition.filters();
            List<FilterCriterion> filters = new ArrayList<>();
            for (ReportDefinitionService.FilterView filter : savedFilters) {
                filters.add(new FilterCriterion(filter.fieldPath(), filter.fieldKind(), filter.operator(),
                        filter.value(), filter.valueTo()));
            }
            if (effectiveParameters.filters() != null) filters.addAll(effectiveParameters.filters());

            List<SubmissionRow> filtered = submissions.stream()
                    .filter(row -> filters.stream().allMatch(filter -> matches(row, filter)))
                    .toList();

            List<ReportDefinitionService.FieldView> configuredFields = definition.fields();
            if (configuredFields.isEmpty()) configuredFields = defaultFields();
            boolean aggregateMode = configuredFields.stream()
                    .anyMatch(field -> field.groupBy() || !"NONE".equalsIgnoreCase(field.aggregation()));

            BuiltResult built = aggregateMode
                    ? buildAggregateRows(configuredFields, filtered)
                    : buildTableRows(configuredFields, filtered);

            long duration = Math.max(0, java.time.Duration.between(started, Instant.now()).toMillis());
            jdbc.update("""
                    UPDATE report_runs
                    SET row_count = ?, source_submission_count = ?, status = 'COMPLETED', duration_ms = ?,
                        completed_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, built.rows().size(), filtered.size(), duration, runId);

            return new ReportResult(runId, reportId, definition.name(), built.columns(), built.rows(),
                    built.rows().size(), filtered.size(), Instant.now());
        } catch (RuntimeException ex) {
            long duration = Math.max(0, java.time.Duration.between(started, Instant.now()).toMillis());
            jdbc.update("""
                    UPDATE report_runs
                    SET status = 'FAILED', duration_ms = ?, error_message = ?, completed_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, duration, safeMessage(ex), runId);
            throw ex;
        }
    }

    private List<SubmissionRow> loadSubmissions(UUID formId, RunParameters parameters) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.id, s.instance_id, s.status, s.submitted_at, s.form_version_id,
                       fv.version AS form_version, s.submitted_by_id, au.username AS submitter, s.metadata
                FROM submissions s
                LEFT JOIN form_versions fv ON fv.id = s.form_version_id
                LEFT JOIN app_users au ON au.id = s.submitted_by_id
                WHERE s.form_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(formId);
        if (parameters.from() != null) {
            sql.append(" AND s.submitted_at >= ?");
            args.add(Timestamp.from(parameters.from()));
        }
        if (parameters.to() != null) {
            sql.append(" AND s.submitted_at <= ?");
            args.add(Timestamp.from(parameters.to()));
        }
        if (parameters.formVersionId() != null) {
            sql.append(" AND s.form_version_id = ?");
            args.add(parameters.formVersionId());
        }
        if (parameters.submitterId() != null) {
            sql.append(" AND s.submitted_by_id = ?");
            args.add(parameters.submitterId());
        }
        sql.append(" ORDER BY s.submitted_at DESC, s.id");

        List<SubmissionRow> rows = jdbc.query(sql.toString(), (rs, rowNum) -> submissionRow(rs), args.toArray());
        if (parameters.sourceClient() != null && !parameters.sourceClient().isBlank()) {
            String wanted = parameters.sourceClient().trim();
            rows = rows.stream().filter(row -> Objects.equals(wanted, String.valueOf(row.system().get("$submission.source")))).toList();
        }
        return new ArrayList<>(rows);
    }

    private SubmissionRow submissionRow(ResultSet rs) throws SQLException {
        UUID id = uuid(rs, "id");
        LinkedHashMap<String, Object> system = new LinkedHashMap<>();
        system.put("$submission.id", id.toString());
        system.put("$submission.instance_id", rs.getString("instance_id"));
        system.put("$submission.status", rs.getString("status"));
        Timestamp submitted = rs.getTimestamp("submitted_at");
        system.put("$submission.submitted_at", submitted == null ? null : submitted.toInstant());
        system.put("$submission.form_version", rs.getString("form_version"));
        system.put("$submission.submitter", rs.getString("submitter"));
        system.put("$submission.source", sourceClient(rs.getString("metadata")));
        return new SubmissionRow(id, system, new LinkedHashMap<>());
    }

    private void loadAnswers(List<SubmissionRow> submissions) {
        if (submissions.isEmpty()) return;
        Map<UUID, SubmissionRow> byId = submissions.stream().collect(Collectors.toMap(
                SubmissionRow::id, row -> row, (a, b) -> a, LinkedHashMap::new));
        String placeholders = submissions.stream().map(ignored -> "?").collect(Collectors.joining(","));
        List<Object> args = submissions.stream().map(SubmissionRow::id).map(id -> (Object) id).toList();
        jdbc.query("""
                SELECT submission_id, path, field_type, value_text, value_number, value_date,
                       value_boolean, value_json, repeat_index, created_at
                FROM submission_answers
                WHERE submission_id IN (%s)
                ORDER BY submission_id, created_at, repeat_index
                """.formatted(placeholders), rs -> {
            UUID submissionId = uuid(rs, "submission_id");
            SubmissionRow row = byId.get(submissionId);
            if (row == null) return;
            String path = ReportDefinitionService.canonicalAnswerPath(rs.getString("path"));
            if (path.isBlank()) return;
            Object value = answerValue(rs);
            if (value == null) return;
            Object existing = row.answers().get(path);
            if (existing == null) {
                row.answers().put(path, value);
            } else if (existing instanceof List<?> existingList) {
                List<Object> values = new ArrayList<>(existingList);
                values.add(value);
                row.answers().put(path, values);
            } else {
                List<Object> values = new ArrayList<>();
                values.add(existing);
                values.add(value);
                row.answers().put(path, values);
            }
        }, args.toArray());
    }

    private Object answerValue(ResultSet rs) throws SQLException {
        BigDecimal number = rs.getBigDecimal("value_number");
        if (number != null) return number.stripTrailingZeros();
        Timestamp date = rs.getTimestamp("value_date");
        if (date != null) return date.toInstant();
        Object booleanObject = rs.getObject("value_boolean");
        if (booleanObject != null) return rs.getBoolean("value_boolean");
        String json = rs.getString("value_json");
        if (json != null && !json.isBlank()) return json;
        return rs.getString("value_text");
    }

    private BuiltResult buildTableRows(List<ReportDefinitionService.FieldView> fields, List<SubmissionRow> submissions) {
        List<ReportColumn> columns = fields.stream().map(this::column).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SubmissionRow submission : submissions) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            for (ReportDefinitionService.FieldView field : fields) {
                row.put(field.fieldPath(), value(submission, field.fieldKind(), field.fieldPath()));
            }
            rows.add(row);
        }
        return new BuiltResult(columns, rows);
    }

    private BuiltResult buildAggregateRows(List<ReportDefinitionService.FieldView> fields, List<SubmissionRow> submissions) {
        List<ReportDefinitionService.FieldView> groups = fields.stream().filter(ReportDefinitionService.FieldView::groupBy).toList();
        List<ReportDefinitionService.FieldView> aggregates = fields.stream()
                .filter(field -> !"NONE".equalsIgnoreCase(field.aggregation())).toList();
        if (aggregates.isEmpty()) {
            aggregates = List.of(new ReportDefinitionService.FieldView(null, "$submission.id", "SYSTEM",
                    "Submission Count", "integer", "COUNT", false, Integer.MAX_VALUE));
        }

        LinkedHashMap<GroupKey, List<SubmissionRow>> grouped = new LinkedHashMap<>();
        for (SubmissionRow submission : submissions) {
            List<Object> keyValues = groups.stream()
                    .map(group -> groupValue(value(submission, group.fieldKind(), group.fieldPath())))
                    .toList();
            grouped.computeIfAbsent(new GroupKey(keyValues), ignored -> new ArrayList<>()).add(submission);
        }
        if (grouped.isEmpty() && groups.isEmpty()) grouped.put(new GroupKey(List.of()), new ArrayList<>());

        List<ReportColumn> columns = new ArrayList<>();
        groups.forEach(group -> columns.add(column(group)));
        aggregates.forEach(aggregate -> columns.add(column(aggregate)));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<GroupKey, List<SubmissionRow>> entry : grouped.entrySet()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < groups.size(); i++) row.put(groups.get(i).fieldPath(), entry.getKey().values().get(i));
            for (ReportDefinitionService.FieldView aggregate : aggregates) {
                row.put(aggregate.fieldPath() + "::" + aggregate.aggregation().toUpperCase(Locale.ROOT),
                        aggregate(entry.getValue(), aggregate));
            }
            rows.add(row);
        }
        return new BuiltResult(columns, rows);
    }

    private Object aggregate(List<SubmissionRow> rows, ReportDefinitionService.FieldView field) {
        String aggregation = field.aggregation().toUpperCase(Locale.ROOT);
        List<Object> values = new ArrayList<>();
        for (SubmissionRow row : rows) flattenValues(value(row, field.fieldKind(), field.fieldPath()), values);
        values.removeIf(this::isEmptyValue);
        return switch (aggregation) {
            case "COUNT" -> values.size();
            case "SUM" -> numeric(values).stream().reduce(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros();
            case "AVG" -> {
                List<BigDecimal> numbers = numeric(values);
                if (numbers.isEmpty()) yield null;
                BigDecimal sum = numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                yield sum.divide(BigDecimal.valueOf(numbers.size()), 6, RoundingMode.HALF_UP).stripTrailingZeros();
            }
            case "MIN" -> minMax(values, true);
            case "MAX" -> minMax(values, false);
            default -> null;
        };
    }

    private Object minMax(List<Object> values, boolean min) {
        if (values.isEmpty()) return null;
        List<BigDecimal> numbers = numeric(values);
        if (numbers.size() == values.size()) {
            return (min ? numbers.stream().min(Comparator.naturalOrder()) : numbers.stream().max(Comparator.naturalOrder()))
                    .orElse(null);
        }
        Comparator<String> comparator = Comparator.naturalOrder();
        return values.stream().map(String::valueOf).reduce((a, b) ->
                (min ? comparator.compare(a, b) <= 0 : comparator.compare(a, b) >= 0) ? a : b).orElse(null);
    }

    private List<BigDecimal> numeric(List<Object> values) {
        List<BigDecimal> numbers = new ArrayList<>();
        for (Object value : values) {
            BigDecimal number = toNumber(value);
            if (number != null) numbers.add(number);
        }
        return numbers;
    }

    private ReportColumn column(ReportDefinitionService.FieldView field) {
        String aggregation = field.aggregation() == null ? "NONE" : field.aggregation().toUpperCase(Locale.ROOT);
        String key = "NONE".equals(aggregation) ? field.fieldPath() : field.fieldPath() + "::" + aggregation;
        String label = field.label() == null || field.label().isBlank() ? field.fieldPath() : field.label();
        if (!"NONE".equals(aggregation)) label = aggregation + " " + label;
        return new ReportColumn(key, label, field.dataType(), field.fieldKind(), aggregation, field.groupBy());
    }

    private List<ReportDefinitionService.FieldView> defaultFields() {
        return List.of(
                new ReportDefinitionService.FieldView(null, "$submission.instance_id", "SYSTEM", "Instance ID", "string", "NONE", false, 0),
                new ReportDefinitionService.FieldView(null, "$submission.submitted_at", "SYSTEM", "Submitted At", "dateTime", "NONE", false, 1),
                new ReportDefinitionService.FieldView(null, "$submission.status", "SYSTEM", "Status", "string", "NONE", false, 2)
        );
    }

    private boolean matches(SubmissionRow row, FilterCriterion filter) {
        if (filter == null || filter.fieldPath() == null || filter.fieldPath().isBlank()) return true;
        Object raw = value(row, filter.fieldKind(), filter.fieldPath());
        List<Object> values = new ArrayList<>();
        flattenValues(raw, values);
        String operator = filter.operator() == null ? "EQ" : filter.operator().toUpperCase(Locale.ROOT);
        boolean empty = values.isEmpty() || values.stream().allMatch(this::isEmptyValue);
        if ("IS_EMPTY".equals(operator)) return empty;
        if ("NOT_EMPTY".equals(operator)) return !empty;
        if (empty) return "NE".equals(operator);

        return switch (operator) {
            case "EQ" -> values.stream().anyMatch(value -> compare(value, filter.value()) == 0);
            case "NE" -> values.stream().noneMatch(value -> compare(value, filter.value()) == 0);
            case "CONTAINS" -> values.stream().anyMatch(value -> String.valueOf(value).toLowerCase(Locale.ROOT)
                    .contains(String.valueOf(filter.value()).toLowerCase(Locale.ROOT)));
            case "GT" -> values.stream().anyMatch(value -> compare(value, filter.value()) > 0);
            case "GTE" -> values.stream().anyMatch(value -> compare(value, filter.value()) >= 0);
            case "LT" -> values.stream().anyMatch(value -> compare(value, filter.value()) < 0);
            case "LTE" -> values.stream().anyMatch(value -> compare(value, filter.value()) <= 0);
            case "IN" -> {
                Set<String> accepted = splitCsv(filter.value()).stream().map(this::normalizedString).collect(Collectors.toSet());
                yield values.stream().map(this::normalizedString).anyMatch(accepted::contains);
            }
            case "BETWEEN" -> values.stream().anyMatch(value -> compare(value, filter.value()) >= 0
                    && compare(value, filter.valueTo()) <= 0);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported filter operator: " + operator);
        };
    }

    private int compare(Object left, String right) {
        if (right == null) return isEmptyValue(left) ? 0 : 1;
        BigDecimal leftNumber = toNumber(left);
        BigDecimal rightNumber = toNumber(right);
        if (leftNumber != null && rightNumber != null) return leftNumber.compareTo(rightNumber);
        Instant leftInstant = toInstant(left);
        Instant rightInstant = toInstant(right);
        if (leftInstant != null && rightInstant != null) return leftInstant.compareTo(rightInstant);
        return normalizedString(left).compareTo(normalizedString(right));
    }

    private Object value(SubmissionRow row, String kind, String path) {
        String normalizedKind = kind == null ? ReportDefinitionService.KIND_ANSWER : kind.toUpperCase(Locale.ROOT);
        return ReportDefinitionService.KIND_SYSTEM.equals(normalizedKind)
                ? row.system().get(path)
                : row.answers().get(ReportDefinitionService.canonicalAnswerPath(path));
    }

    private static Object groupValue(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).collect(Collectors.joining(" | "));
        }
        return value;
    }

    private void flattenValues(Object value, List<Object> target) {
        if (value == null) return;
        if (value instanceof Collection<?> collection) collection.forEach(item -> flattenValues(item, target));
        else target.add(value);
    }

    private boolean isEmptyValue(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    private BigDecimal toNumber(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        try { return new BigDecimal(String.valueOf(value).trim()); }
        catch (NumberFormatException ex) { return null; }
    }

    private Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return null;
        try { return Instant.parse(text); }
        catch (DateTimeParseException ignored) {}
        try { return OffsetDateTime.parse(text).toInstant(); }
        catch (DateTimeParseException ignored) {}
        try { return LocalDate.parse(text).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private String normalizedString(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    private String sourceClient(String metadata) {
        if (metadata == null || metadata.isBlank()) return "Unknown";
        try {
            JsonNode root = objectMapper.readTree(metadata);
            JsonNode value = root.get("sourceClient");
            return value == null || value.isNull() || value.asText().isBlank() ? "Unknown" : value.asText();
        } catch (Exception ex) {
            return "Unknown";
        }
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { return "{}"; }
    }

    private Optional<UUID> currentUserId() {
        try {
            var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || authentication.getName() == null || "anonymousUser".equals(authentication.getName())) return Optional.empty();
            List<UUID> ids = jdbc.query("SELECT id FROM users WHERE username = ?", (rs, rowNum) -> uuid(rs, "id"), authentication.getName());
            return ids.stream().findFirst();
        } catch (RuntimeException ex) { return Optional.empty(); }
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) message = throwable.getClass().getSimpleName();
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    private record SubmissionRow(UUID id, LinkedHashMap<String, Object> system, LinkedHashMap<String, Object> answers) {}
    private record GroupKey(List<Object> values) {}
    private record BuiltResult(List<ReportColumn> columns, List<Map<String, Object>> rows) {}

    public record FilterCriterion(String fieldPath, String fieldKind, String operator, String value, String valueTo) {}
    public record RunParameters(Instant from, Instant to, UUID formVersionId, UUID submitterId,
                                String sourceClient, List<FilterCriterion> filters) {}
    public record ReportColumn(String key, String label, String dataType, String fieldKind,
                               String aggregation, boolean groupBy) {}
    public record ReportResult(UUID runId, UUID reportId, String reportName, List<ReportColumn> columns,
                               List<Map<String, Object>> rows, int rowCount, int sourceSubmissionCount,
                               Instant executedAt) {}
}
