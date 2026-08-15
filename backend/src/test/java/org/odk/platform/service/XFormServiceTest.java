package org.odk.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.XFormRepository;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class XFormServiceTest {
    @Mock XFormRepository repository;
    @InjectMocks XFormService service;

    @Test
    void delegatesScopedLookupSaveAndDelete() {
        UUID formId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        XForm form = XForm.builder().id(formId).xmlns("demo").build();
        when(repository.findByIdAndProjectId(formId, projectId)).thenReturn(Optional.of(form));
        when(repository.save(form)).thenReturn(form);
        assertSame(form, service.findByIdAndProjectId(formId, projectId).orElseThrow());
        assertSame(form, service.save(form));
        service.deleteById(formId);
        verify(repository).deleteById(formId);
    }
}
