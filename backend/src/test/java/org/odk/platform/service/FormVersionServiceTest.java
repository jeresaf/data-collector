package org.odk.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.repository.FormVersionRepository;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FormVersionServiceTest {
    @Mock FormVersionRepository repository;
    @InjectMocks FormVersionService service;

    @Test
    void delegatesLatestLookupAndPersistence() {
        UUID formId = UUID.randomUUID();
        FormVersion version = FormVersion.builder().id(UUID.randomUUID()).version("2").build();
        when(repository.findLatestByFormId(formId)).thenReturn(Optional.of(version));
        when(repository.save(version)).thenReturn(version);
        assertSame(version, service.findLatestByFormId(formId).orElseThrow());
        assertSame(version, service.save(version));
        assertFalse(service.existsById(UUID.randomUUID()));
    }
}
