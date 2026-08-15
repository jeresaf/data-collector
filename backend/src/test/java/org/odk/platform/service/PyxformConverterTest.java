package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PyxformConverterTest {
    @Test
    void unavailableCommandIsReportedAndConversionFailsClearly() {
        PyxformConverter converter = new PyxformConverter("definitely-not-a-real-pyxform-command-odk", 1);
        assertFalse(converter.isAvailable());
        PyxformConverter.ConversionException ex = assertThrows(
                PyxformConverter.ConversionException.class,
                () -> converter.convert(new byte[]{1, 2, 3}, "sample.xlsx"));
        assertTrue(ex.getMessage().contains("Unable to start pyxform command"));
    }
}
