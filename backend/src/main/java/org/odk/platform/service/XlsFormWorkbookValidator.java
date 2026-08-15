package org.odk.platform.service;

import lombok.Builder;
import lombok.Value;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Performs a lightweight, template-aware XLSForm preflight before pyxform is
 * invoked. pyxform remains the authoritative XLSForm converter; this class is
 * intentionally limited to early feedback, source preservation metadata and
 * guardrails that are useful to the admin UI.
 */
@Service
public class XlsFormWorkbookValidator {

    private static final Set<String> SURVEY_BASE_COLUMNS = Set.of(
            "type", "name", "label", "hint", "required", "relevant", "appearance",
            "default", "constraint", "constraint_message", "calculation", "trigger",
            "choice_filter", "parameters", "repeat_count", "note", "image", "audio",
            "video", "guidance_hint", "required_message", "save_to");

    private static final Set<String> CHOICES_BASE_COLUMNS = Set.of(
            "list_name", "name", "label", "image", "audio", "video");

    private static final Set<String> SETTINGS_BASE_COLUMNS = Set.of(
            "form_title", "form_id", "version", "instance_name", "default_language",
            "style", "submission_url", "public_key", "auto_send", "auto_delete");

    private static final Set<String> ENTITIES_BASE_COLUMNS = Set.of("list_name", "label");

    private static final Set<String> TEMPLATE_HELPER_SHEETS = Set.of(
            "👋 start here", "⚙️ types", "👀 appearances", "🔀 relevance",
            "🔒 constraints", "🌐 translations", "🔍 list lookups", "✅ additional columns");

    public ValidationResult validate(byte[] workbookBytes, String originalFilename) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> info = new ArrayList<>();
        Map<String, List<String>> headersBySheet = new LinkedHashMap<>();
        Map<String, List<String>> extraColumnsBySheet = new LinkedHashMap<>();

        if (originalFilename == null || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            errors.add("Only .xlsx XLSForm workbooks are supported");
            return ValidationResult.builder()
                    .valid(false)
                    .errors(errors)
                    .warnings(warnings)
                    .info(info)
                    .sheetHeaders(headersBySheet)
                    .extraColumns(extraColumnsBySheet)
                    .build();
        }

        if (workbookBytes == null || workbookBytes.length == 0) {
            errors.add("XLSForm workbook is empty");
            return ValidationResult.builder()
                    .valid(false)
                    .errors(errors)
                    .warnings(warnings)
                    .info(info)
                    .sheetHeaders(headersBySheet)
                    .extraColumns(extraColumnsBySheet)
                    .build();
        }

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes))) {
            Map<String, Sheet> sheets = new LinkedHashMap<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                sheets.put(sheet.getSheetName().toLowerCase(Locale.ROOT), sheet);
                headersBySheet.put(sheet.getSheetName(), readHeaders(sheet));
            }

            Sheet survey = sheets.get("survey");
            if (survey == null) {
                errors.add("Required sheet 'survey' is missing");
            } else {
                validateRequiredHeaders("survey", headersBySheet.get(survey.getSheetName()),
                        Set.of("type", "name"), errors);
                collectExtraColumns("survey", headersBySheet.get(survey.getSheetName()),
                        SURVEY_BASE_COLUMNS, extraColumnsBySheet, warnings);
            }

            Sheet settings = sheets.get("settings");
            if (settings == null) {
                warnings.add("Sheet 'settings' is missing; pyxform may generate defaults, but form_id/version metadata should be supplied explicitly");
            } else {
                validateRequiredHeaders("settings", headersBySheet.get(settings.getSheetName()),
                        Set.of("form_title", "form_id"), errors);
                collectExtraColumns("settings", headersBySheet.get(settings.getSheetName()),
                        SETTINGS_BASE_COLUMNS, extraColumnsBySheet, warnings);
            }

            Sheet choices = sheets.get("choices");
            if (choices != null) {
                validateRequiredHeaders("choices", headersBySheet.get(choices.getSheetName()),
                        Set.of("list_name", "name", "label"), errors);
                collectExtraColumns("choices", headersBySheet.get(choices.getSheetName()),
                        CHOICES_BASE_COLUMNS, extraColumnsBySheet, warnings);
            } else if (survey != null && surveyUsesInternalChoices(survey)) {
                errors.add("Sheet 'choices' is required because the survey contains select_one/select_multiple questions backed by internal choice lists");
            }

            Sheet entities = sheets.get("entities");
            if (entities != null) {
                validateRequiredHeaders("entities", headersBySheet.get(entities.getSheetName()),
                        Set.of("list_name", "label"), errors);
                collectExtraColumns("entities", headersBySheet.get(entities.getSheetName()),
                        ENTITIES_BASE_COLUMNS, extraColumnsBySheet, warnings);
            }

            for (Sheet sheet : sheets.values()) {
                String normalized = sheet.getSheetName().toLowerCase(Locale.ROOT);
                if (!Set.of("survey", "choices", "settings", "entities").contains(normalized)
                        && !TEMPLATE_HELPER_SHEETS.contains(normalized)) {
                    info.add("Additional sheet '" + sheet.getSheetName() + "' will be preserved in the original XLSX and passed unchanged to pyxform");
                }
            }

            info.add("Workbook contains " + workbook.getNumberOfSheets() + " sheet(s)");
        } catch (IOException | RuntimeException e) {
            errors.add("Unable to read XLSX workbook: " + safeMessage(e));
        }

        return ValidationResult.builder()
                .valid(errors.isEmpty())
                .errors(List.copyOf(errors))
                .warnings(List.copyOf(warnings))
                .info(List.copyOf(info))
                .sheetHeaders(copyMap(headersBySheet))
                .extraColumns(copyMap(extraColumnsBySheet))
                .build();
    }

    private List<String> readHeaders(Sheet sheet) {
        Row headerRow = firstNonEmptyRow(sheet);
        if (headerRow == null) {
            return List.of();
        }
        List<String> headers = new ArrayList<>();
        int lastCell = Math.max(headerRow.getLastCellNum(), 0);
        for (int i = 0; i < lastCell; i++) {
            Cell cell = headerRow.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            String value = cell == null ? "" : cellToString(cell).trim();
            if (!value.isEmpty()) {
                headers.add(value);
            }
        }
        return headers;
    }

    private Row firstNonEmptyRow(Sheet sheet) {
        int max = Math.min(sheet.getLastRowNum(), 50);
        for (int i = sheet.getFirstRowNum(); i <= max; i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            for (Cell cell : row) {
                if (!cellToString(cell).trim().isEmpty()) {
                    return row;
                }
            }
        }
        return null;
    }

    private String cellToString(Cell cell) {
        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue();
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
        }
        if (cell.getCellType() == CellType.BOOLEAN) {
            return Boolean.toString(cell.getBooleanCellValue());
        }
        if (cell.getCellType() == CellType.FORMULA) {
            try {
                return cell.getStringCellValue();
            } catch (IllegalStateException ignored) {
                return cell.getCellFormula();
            }
        }
        return "";
    }

    private void validateRequiredHeaders(String sheetName, List<String> headers,
                                         Set<String> required, List<String> errors) {
        Set<String> normalized = normalize(headers);
        for (String requiredHeader : required) {
            if (!normalized.contains(requiredHeader)) {
                errors.add("Sheet '" + sheetName + "' is missing required column '" + requiredHeader + "'");
            }
        }
    }

    private void collectExtraColumns(String sheetName, List<String> headers, Set<String> known,
                                     Map<String, List<String>> extrasBySheet, List<String> warnings) {
        List<String> extras = new ArrayList<>();
        for (String header : headers) {
            String normalized = normalizeHeader(header);
            if (!known.contains(normalized) && !isKnownExtensionColumn(normalized)) {
                extras.add(header);
                warnings.add("Column '" + header + "' on sheet '" + sheetName
                        + "' is not in the platform compatibility catalog; it is preserved and passed to pyxform unchanged");
            }
        }
        if (!extras.isEmpty()) {
            extrasBySheet.put(sheetName, List.copyOf(extras));
        }
    }

    private boolean isKnownExtensionColumn(String normalized) {
        return normalized.startsWith("label::")
                || normalized.startsWith("hint::")
                || normalized.startsWith("guidance_hint::")
                || normalized.startsWith("constraint_message::")
                || normalized.startsWith("required_message::")
                || normalized.startsWith("image::")
                || normalized.startsWith("audio::")
                || normalized.startsWith("video::")
                || normalized.startsWith("bind::")
                || normalized.startsWith("body::");
    }

    private Set<String> normalize(List<String> headers) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String header : headers) {
            normalized.add(normalizeHeader(header));
        }
        return normalized;
    }

    private String normalizeHeader(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private boolean surveyUsesInternalChoices(Sheet survey) {
        Row header = firstNonEmptyRow(survey);
        if (header == null) return false;
        int typeColumn = -1;
        for (Cell cell : header) {
            if ("type".equals(normalizeHeader(cellToString(cell)))) {
                typeColumn = cell.getColumnIndex();
                break;
            }
        }
        if (typeColumn < 0) return false;
        for (int i = header.getRowNum() + 1; i <= survey.getLastRowNum(); i++) {
            Row row = survey.getRow(i);
            if (row == null) continue;
            Cell typeCell = row.getCell(typeColumn, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (typeCell == null) continue;
            String type = cellToString(typeCell).trim().toLowerCase(Locale.ROOT);
            if ((type.startsWith("select_one ") || type.startsWith("select_multiple "))
                    && !type.contains("_from_file") && !type.contains(" from file")) {
                return true;
            }
        }
        return false;
    }

    private Map<String, List<String>> copyMap(Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null || throwable.getMessage().isBlank()
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }

    @Value
    @Builder
    public static class ValidationResult {
        boolean valid;
        @Builder.Default List<String> errors = List.of();
        @Builder.Default List<String> warnings = List.of();
        @Builder.Default List<String> info = List.of();
        @Builder.Default Map<String, List<String>> sheetHeaders = Map.of();
        @Builder.Default Map<String, List<String>> extraColumns = Map.of();
    }
}
