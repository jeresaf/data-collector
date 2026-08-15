package org.odk.platform.service;

import org.javarosa.core.model.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stable JavaRosa 4.3.2 certification fixtures.
 *
 * These files are intentionally kept in source control so compatibility changes
 * are measured against the same representative XForms on every build.
 */
class GoldenFormCompatibilityTest {

    @ParameterizedTest(name = "JavaRosa parses golden form {0}")
    @ValueSource(strings = {
            "core-types.xml",
            "groups-repeats.xml",
            "geo-media.xml"
    })
    void goldenForms_parseThroughJavaRosaAndPlatformWrapper(String fixture) throws Exception {
        String xml = fixture(fixture);

        JavaRosaFormLoader loader = new JavaRosaFormLoader();
        assertDoesNotThrow(() -> loader.loadFormDef(xml));

        FormDefinition definition = assertDoesNotThrow(() ->
                FormDefinition.createNew(xml, "golden-" + fixture));
        assertNotNull(definition.getFormDef());
        assertTrue(definition.getCompatibilityWarnings().stream()
                .noneMatch(warning -> warning.toLowerCase().contains("failed to parse")));
    }

    @Test
    void coreGoldenForm_keepsExpectedCalculateSemantics() throws Exception {
        FormDefinition definition = FormDefinition.createNew(fixture("core-types.xml"), "golden-core");

        assertEquals(2.0, ((Number) definition.evaluateXPath("1 + 1")).doubleValue(), 0.000001);
        assertTrue((Boolean) definition.evaluateXPath("/data/age = ''"));
    }

    @Test
    void geoMediaGoldenForm_keepsUploadAndSignatureHints() throws Exception {
        FormDefinition definition = FormDefinition.createNew(fixture("geo-media.xml"), "golden-geo");

        // point
        definition.stepToNextEvent();
        assertEquals(Constants.DATATYPE_GEOPOINT, definition.getCurrentDataType());

        // photo
        definition.stepToNextEvent();
        assertEquals("image/*", definition.getCurrentMediaType());

        // signature
        definition.stepToNextEvent();
        assertEquals("signature", definition.getCurrentAppearanceHint());
        assertEquals("image/*", definition.getCurrentMediaType());
    }

    private static String fixture(String name) throws IOException {
        String path = "/golden-forms/" + name;
        try (InputStream stream = GoldenFormCompatibilityTest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing golden form fixture: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
