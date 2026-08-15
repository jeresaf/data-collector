package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportExportService {

    private final ReportRunService runner;
    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    public ExportPayload export(UUID projectId, UUID reportId, String requestedFormat,
                                ReportRunService.RunParameters parameters) {
        String format = requestedFormat == null ? "CSV" : requestedFormat.toUpperCase(Locale.ROOT);
        if (!"CSV".equals(format) && !"XLSX".equals(format)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Export format must be CSV or XLSX");
        }
        ReportRunService.ReportResult result = runner.run(projectId, reportId, parameters);
        byte[] bytes = "XLSX".equals(format) ? xlsx(result) : csv(result);
        String extension = "XLSX".equals(format) ? "xlsx" : "csv";
        String contentType = "XLSX".equals(format)
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "text/csv;charset=UTF-8";
        String filename = safeFilename(result.reportName()) + "-" + Instant.now().toString().replace(':', '-') + "." + extension;
        UUID exportId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO report_exports
                    (id, report_run_id, format, file_name, content_type, file_size, created_by_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, exportId, result.runId(), format, filename, contentType, bytes.length, currentUserId().orElse(null));
        auditService.log(AuditActions.REPORT_EXPORTED, "REPORT", reportId, result.reportName(), Map.of(
                "projectId", projectId.toString(),
                "reportRunId", result.runId().toString(),
                "exportId", exportId.toString(),
                "format", format,
                "fileName", filename,
                "fileSize", bytes.length));
        return new ExportPayload(exportId, result.runId(), filename, contentType, bytes);
    }

    byte[] csv(ReportRunService.ReportResult result) {
        StringBuilder csv = new StringBuilder();
        csv.append(result.columns().stream().map(column -> escapeCsv(column.label())).collect(Collectors.joining(","))).append('\n');
        for (var row : result.rows()) {
            for (int i = 0; i < result.columns().size(); i++) {
                if (i > 0) csv.append(',');
                csv.append(escapeCsv(display(row.get(result.columns().get(i).key()))));
            }
            csv.append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    byte[] xlsx(ReportRunService.ReportResult result) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Report");
            Row header = sheet.createRow(0);
            for (int i = 0; i < result.columns().size(); i++) {
                header.createCell(i).setCellValue(result.columns().get(i).label());
            }
            for (int rowIndex = 0; rowIndex < result.rows().size(); rowIndex++) {
                Row excelRow = sheet.createRow(rowIndex + 1);
                var data = result.rows().get(rowIndex);
                for (int columnIndex = 0; columnIndex < result.columns().size(); columnIndex++) {
                    Object value = data.get(result.columns().get(columnIndex).key());
                    writeCell(excelRow.createCell(columnIndex), value);
                }
            }
            for (int i = 0; i < Math.min(result.columns().size(), 50); i++) sheet.autoSizeColumn(i);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create XLSX export", ex);
        }
    }

    private void writeCell(Cell cell, Object value) {
        if (value == null) return;
        if (value instanceof Number number) cell.setCellValue(number.doubleValue());
        else if (value instanceof Boolean bool) cell.setCellValue(bool);
        else cell.setCellValue(display(value));
    }

    private String display(Object value) {
        if (value == null) return "";
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).collect(Collectors.joining(" | "));
        }
        return String.valueOf(value);
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String safeFilename(String name) {
        String safe = name == null ? "report" : name.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        safe = safe.replaceAll("-+", "-").replaceAll("^-|-$", "");
        return safe.isBlank() ? "report" : safe;
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

    public record ExportPayload(UUID exportId, UUID runId, String filename, String contentType, byte[] bytes) {}
}
