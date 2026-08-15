package org.odk.platform.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.XForm;
import org.odk.platform.service.WebFormAccessService;
import org.odk.platform.service.WebFormRuntimeService;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebFormControllerAuditTest {

    @Mock WebFormRuntimeService webFormRuntimeService;
    @Mock WebFormAccessService webFormAccessService;
    @Mock AuditService auditService;
    @InjectMocks WebFormController controller;

    @Test
    void finalize_emitsSubmissionReceivedAudit() {
        UUID sessionId = UUID.randomUUID();
        XForm form = XForm.builder()
                .id(UUID.randomUUID())
                .project(Project.builder().id(UUID.randomUUID()).name("Audit").build())
                .title("Web Audit")
                .build();
        FormVersion version = FormVersion.builder().id(UUID.randomUUID()).form(form).version("1").build();
        Submission submission = Submission.builder()
                .id(UUID.randomUUID())
                .form(form)
                .formVersion(version)
                .instanceId("uuid:web-audit")
                .build();
        when(webFormRuntimeService.finalizeSession(sessionId)).thenReturn(submission);

        var response = controller.finalizeSession(sessionId);
        assertEquals(200, response.getStatusCode().value());
        verify(auditService).log(
                eq(AuditActions.SUBMISSION_RECEIVED),
                eq("SUBMISSION"),
                eq(submission.getId()),
                eq("uuid:web-audit"),
                anyMap());
    }

    @Test
    void finalizeFailure_emitsErrorAudit() {
        UUID sessionId = UUID.randomUUID();
        when(webFormRuntimeService.finalizeSession(sessionId)).thenThrow(new IllegalStateException("invalid form"));

        var response = controller.finalizeSession(sessionId);
        assertEquals(400, response.getStatusCode().value());
        verify(auditService).logError(
                eq(AuditActions.SUBMISSION_RECEIVED),
                eq("WEB_FORM_SESSION"),
                eq(sessionId),
                eq(sessionId.toString()),
                anyMap(),
                eq("invalid form"));
    }
}
