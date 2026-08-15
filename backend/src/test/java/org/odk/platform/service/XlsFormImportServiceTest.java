package org.odk.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.User;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class XlsFormImportServiceTest {
    @Mock XlsFormWorkbookValidator workbookValidator;
    @Mock PyxformConverter pyxformConverter;
    @Mock JavaRosaFormValidator javaRosaFormValidator;
    @Mock JavaRosaFormLoader javaRosaFormLoader;
    @Mock FormMetadataExtractor formMetadataExtractor;
    @Mock XFormService xFormService;
    @Mock FormVersionService formVersionService;
    @InjectMocks XlsFormImportService service;

    @Test
    void validatesRequiredArgumentsBeforeConversion() {
        assertThrows(IllegalArgumentException.class,
                () -> service.importWorkbook(null, new byte[]{1}, "a.xlsx", null, null, new User()));
        assertThrows(IllegalArgumentException.class,
                () -> service.importWorkbook(new Project(), new byte[]{1}, "a.xlsx", null, null, null));
        assertThrows(XlsFormImportService.ValidationException.class,
                () -> service.importWorkbook(new Project(), new byte[0], "a.xlsx", null, null, new User()));
        verifyNoInteractions(pyxformConverter);
    }

    @Test
    void preflightErrorsStopPipeline() {
        when(workbookValidator.validate(any(), eq("bad.xlsx"))).thenReturn(
                XlsFormWorkbookValidator.ValidationResult.builder()
                        .errors(List.of("survey sheet missing"))
                        .warnings(List.of("warning"))
                        .info(List.of("info"))
                        .sheetHeaders(Map.of())
                        .extraColumns(Map.of())
                        .build());
        var ex = assertThrows(XlsFormImportService.ValidationException.class,
                () -> service.importWorkbook(new Project(), new byte[]{1}, "bad.xlsx", null, null, new User()));
        assertEquals(List.of("survey sheet missing"), ex.getErrors());
        verifyNoInteractions(pyxformConverter);
    }
}
