package org.odk.platform.service;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional smoke test against the locally installed official pyxform CLI.
 * It is skipped when xls2xform is not on PATH, keeping the normal Maven suite
 * environment-independent.
 */
class PyxformConverterIntegrationTest {

    @Test
    void convertsMinimalXlsForm_whenPyxformIsInstalled() throws Exception {
        PyxformConverter converter = new PyxformConverter(
                System.getenv().getOrDefault("PYXFORM_COMMAND", "xls2xform"), 60);
        Assumptions.assumeTrue(converter.isAvailable(), "xls2xform is not installed");

        PyxformConverter.ConversionResult result = converter.convert(minimalWorkbook(), "people.xlsx");

        assertThat(result.getXml()).contains("people");
        assertThat(result.getXml()).contains("<h:html");
    }

    private byte[] minimalWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var survey = workbook.createSheet("survey");
            var surveyHeader = survey.createRow(0);
            surveyHeader.createCell(0).setCellValue("type");
            surveyHeader.createCell(1).setCellValue("name");
            surveyHeader.createCell(2).setCellValue("label");
            var question = survey.createRow(1);
            question.createCell(0).setCellValue("text");
            question.createCell(1).setCellValue("name");
            question.createCell(2).setCellValue("Name");

            var settings = workbook.createSheet("settings");
            var settingsHeader = settings.createRow(0);
            settingsHeader.createCell(0).setCellValue("form_title");
            settingsHeader.createCell(1).setCellValue("form_id");
            settingsHeader.createCell(2).setCellValue("version");
            var settingsRow = settings.createRow(1);
            settingsRow.createCell(0).setCellValue("People");
            settingsRow.createCell(1).setCellValue("people");
            settingsRow.createCell(2).setCellValue("1");

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
