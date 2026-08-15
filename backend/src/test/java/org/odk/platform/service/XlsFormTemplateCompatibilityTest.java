package org.odk.platform.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class XlsFormTemplateCompatibilityTest {

    @Test
    void authoritativeOdkTemplatePassesWorkbookPreflight() throws Exception {
        Path template = Path.of("..", "docs", "reference", "ODK XLSForm Template.xlsx").normalize();
        Assumptions.assumeTrue(Files.exists(template), "ODK XLSForm Template.xlsx is not available");

        XlsFormWorkbookValidator.ValidationResult result = new XlsFormWorkbookValidator()
                .validate(Files.readAllBytes(template), template.getFileName().toString());

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getSheetHeaders()).containsKeys("survey", "choices", "settings", "entities");
        assertThat(result.getSheetHeaders().get("survey")).contains("type", "name", "label");
        assertThat(result.getSheetHeaders().get("settings")).contains("form_title", "form_id", "version");
    }
}
