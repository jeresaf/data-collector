package org.odk.platform.controller;

import org.junit.jupiter.api.Test;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.XForm;
import org.odk.platform.service.FormAttachmentService;
import org.odk.platform.service.FormExternalDatasetService;
import org.odk.platform.service.FormVersionService;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FormControllerTest {
    @Test
    void formVersionMustBelongToPathForm() {
        FormVersionService versions = mock(FormVersionService.class);
        FormController controller = new FormController(
                versions, mock(FormAttachmentService.class), mock(FormExternalDatasetService.class));
        UUID formId = UUID.randomUUID(), versionId = UUID.randomUUID();
        FormVersion version = FormVersion.builder()
                .id(versionId)
                .form(XForm.builder().id(formId).build())
                .version("1")
                .build();
        when(versions.findById(versionId)).thenReturn(Optional.of(version));
        assertEquals(200, controller.getFormVersion(formId, versionId).getStatusCode().value());
        assertEquals(404, controller.getFormVersion(UUID.randomUUID(), versionId).getStatusCode().value());
    }

    @Test
    void attachmentDownloadPreservesContentMetadata() {
        FormAttachmentService attachments = mock(FormAttachmentService.class);
        FormController controller = new FormController(
                mock(FormVersionService.class), attachments, mock(FormExternalDatasetService.class));
        UUID attachmentId = UUID.randomUUID();
        when(attachments.downloadAttachment(attachmentId)).thenReturn(new byte[]{1,2,3});
        when(attachments.getAttachmentContentType(attachmentId)).thenReturn("image/png");
        when(attachments.getAttachmentFileName(attachmentId)).thenReturn("map.png");
        var response = controller.downloadAttachment(UUID.randomUUID(), UUID.randomUUID(), attachmentId);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("image/png", response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getFirst("Content-Disposition").contains("map.png"));
        assertEquals(3, response.getHeaders().getContentLength());
    }
}
