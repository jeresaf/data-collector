package org.odk.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.domain.FormAttachment;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.repository.FormAttachmentRepository;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.security.CurrentUserService;
import org.odk.platform.service.storage.StorageServiceFactory;
import org.odk.platform.web.exception.ResourceNotFoundException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FormAttachmentServiceTest {
    @Mock FormAttachmentRepository attachments;
    @Mock FormVersionRepository versions;
    @Mock StorageServiceFactory storage;
    @Mock CurrentUserService users;

    @Test
    void lookupMapsAttachmentAndMissingVersionIsRejected() {
        FormAttachmentService service = new FormAttachmentService(attachments, versions, storage, users);
        UUID attachmentId = UUID.randomUUID();
        FormAttachment attachment = FormAttachment.builder()
                .id(attachmentId).fileName("photo.jpg").contentType("image/jpeg").fileSize(3L)
                .formVersion(FormVersion.builder().id(UUID.randomUUID()).version("1").build()).build();
        when(attachments.findById(attachmentId)).thenReturn(Optional.of(attachment));
        var dto = service.getAttachmentById(attachmentId).orElseThrow();
        assertEquals("photo.jpg", dto.getFileName());
        UUID versionId = UUID.randomUUID();
        when(versions.findById(versionId)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.getAllAttachmentsByFormVersion(versionId));
    }
}
