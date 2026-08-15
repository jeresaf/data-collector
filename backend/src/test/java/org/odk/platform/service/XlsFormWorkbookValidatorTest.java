package org.odk.platform.service;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class XlsFormWorkbookValidatorTest {

    private final XlsFormWorkbookValidator validator = new XlsFormWorkbookValidator();

    @Test
    void validWorkbook_preservesHeadersAndWarnsAboutUnknownSurveyColumn() throws Exception {
        byte[] workbook = workbook(true, true, "custom_extension");

        XlsFormWorkbookValidator.ValidationResult result = validator.validate(workbook, "people.xlsx");

        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getSheetHeaders().get("survey"))
                .containsExactly("type", "name", "label", "custom_extension");
        assertThat(result.getExtraColumns().get("survey")).containsExactly("custom_extension");
        assertThat(result.getWarnings()).anyMatch(message -> message.contains("custom_extension"));
    }

    @Test
    void missingSurvey_isBlockingError() throws Exception {
        byte[] workbook = workbook(false, true, null);

        XlsFormWorkbookValidator.ValidationResult result = validator.validate(workbook, "people.xlsx");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).contains("Required sheet 'survey' is missing");
    }

    @Test
    void settingsMayBeMissing_butProducesWarning() throws Exception {
        byte[] workbook = workbook(true, false, null);

        XlsFormWorkbookValidator.ValidationResult result = validator.validate(workbook, "people.xlsx");

        assertThat(result.isValid()).isTrue();
        assertThat(result.getWarnings()).anyMatch(message -> message.contains("settings"));
    }

    @Test
    void internalSelectRequiresChoicesSheet() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var survey = workbook.createSheet("survey");
            var header = survey.createRow(0);
            header.createCell(0).setCellValue("type");
            header.createCell(1).setCellValue("name");
            header.createCell(2).setCellValue("label");
            var row = survey.createRow(1);
            row.createCell(0).setCellValue("select_one yes_no");
            row.createCell(1).setCellValue("answer");
            row.createCell(2).setCellValue("Answer");

            var settings = workbook.createSheet("settings");
            var settingsHeader = settings.createRow(0);
            settingsHeader.createCell(0).setCellValue("form_title");
            settingsHeader.createCell(1).setCellValue("form_id");
            var settingsRow = settings.createRow(1);
            settingsRow.createCell(0).setCellValue("People");
            settingsRow.createCell(1).setCellValue("people");

            XlsFormWorkbookValidator.ValidationResult result =
                    validator.validate(toBytes(workbook), "people.xlsx");

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).anyMatch(message -> message.contains("choices"));
        }
    }

    @Test
    void rejectsNonXlsxFilename() throws Exception {
        XlsFormWorkbookValidator.ValidationResult result =
                validator.validate(workbook(true, true, null), "people.xls");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).contains("Only .xlsx XLSForm workbooks are supported");
    }

    private byte[] workbook(boolean includeSurvey, boolean includeSettings, String extraSurveyColumn) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            if (includeSurvey) {
                var survey = workbook.createSheet("survey");
                var header = survey.createRow(0);
                header.createCell(0).setCellValue("type");
                header.createCell(1).setCellValue("name");
                header.createCell(2).setCellValue("label");
                if (extraSurveyColumn != null) header.createCell(3).setCellValue(extraSurveyColumn);
                var row = survey.createRow(1);
                row.createCell(0).setCellValue("text");
                row.createCell(1).setCellValue("name");
                row.createCell(2).setCellValue("Name");
            }
            if (includeSettings) {
                var settings = workbook.createSheet("settings");
                var header = settings.createRow(0);
                header.createCell(0).setCellValue("form_title");
                header.createCell(1).setCellValue("form_id");
                header.createCell(2).setCellValue("version");
                var row = settings.createRow(1);
                row.createCell(0).setCellValue("People");
                row.createCell(1).setCellValue("people");
                row.createCell(2).setCellValue("1");
            }
            return toBytes(workbook);
        }
    }

    private byte[] toBytes(XSSFWorkbook workbook) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }
}
