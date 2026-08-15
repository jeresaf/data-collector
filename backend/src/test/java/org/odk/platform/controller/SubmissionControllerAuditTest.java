package org.odk.platform.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.XForm;
import org.odk.platform.service.SubmissionService;
import org.odk.platform.service.storage.StorageServiceFactory;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubmissionControllerAuditTest {

    @Mock SubmissionService submissionService;
    @Mock StorageServiceFactory storageServiceFactory;
    @Mock AuditService auditService;
    @InjectMocks SubmissionController controller;

    @Test
    void statusChange_emitsAuditWithOldAndNewState() {
        UUID submissionId = UUID.randomUUID();
        XForm form = XForm.builder()
                .id(UUID.randomUUID())
                .project(Project.builder().id(UUID.randomUUID()).name("Audit").build())
                .title("Status Audit")
                .xmlns("status_audit")
                .build();
        Submission before = Submission.builder()
                .id(submissionId)
                .form(form)
                .instanceId("uuid:status-audit")
                .status("SUBMITTED")
                .build();
        Submission after = Submission.builder()
                .id(submissionId)
                .form(form)
                .instanceId("uuid:status-audit")
                .status("APPROVED")
                .reviewNotes("reviewed")
                .build();
        when(submissionService.findById(submissionId)).thenReturn(Optional.of(before));
        when(submissionService.updateStatus(submissionId, "APPROVED", "reviewed"))
                .thenReturn(Optional.of(after));

        SubmissionController.UpdateStatusRequest request = new SubmissionController.UpdateStatusRequest();
        request.setStatus("APPROVED");
        request.setReviewNotes("reviewed");

        var response = controller.updateStatus(submissionId, request);
        assertEquals(200, response.getStatusCode().value());
        verify(auditService).log(
                eq(AuditActions.SUBMISSION_STATUS_CHANGED),
                eq("SUBMISSION"),
                eq(submissionId),
                eq("uuid:status-audit"),
                argThat(details -> "SUBMITTED".equals(details.get("oldStatus"))
                        && "APPROVED".equals(details.get("newStatus"))));
    }
}
