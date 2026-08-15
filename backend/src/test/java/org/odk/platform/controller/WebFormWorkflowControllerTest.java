package org.odk.platform.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.service.WebFormWorkflowService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebFormWorkflowControllerTest {

    @Mock
    private WebFormWorkflowService workflowService;

    @InjectMocks
    private WebFormWorkflowController controller;

    @Test
    void delegatesCatalogAndDraftProjectionToWorkflowService() {
        UUID formId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        var form = new WebFormWorkflowService.AvailableFormView(
                formId,
                "Household Survey",
                "Description",
                projectId,
                "Field Project",
                versionId,
                "3",
                Instant.parse("2026-08-15T08:00:00Z"));

        var draft = new WebFormWorkflowService.SessionSummaryView(
                sessionId,
                formId,
                "Household Survey",
                versionId,
                "3",
                projectId,
                "Field Project",
                "draft",
                Instant.parse("2026-08-15T08:00:00Z"),
                Instant.parse("2026-08-15T09:00:00Z"),
                null,
                null,
                null,
                null);

        when(workflowService.listAvailableForms()).thenReturn(List.of(form));
        when(workflowService.listSessions("draft")).thenReturn(List.of(draft));

        assertThat(controller.listAvailableForms())
                .extracting(WebFormWorkflowService.AvailableFormView::formId)
                .containsExactly(formId);

        assertThat(controller.listSessions("draft"))
                .extracting(WebFormWorkflowService.SessionSummaryView::sessionId)
                .containsExactly(sessionId);
    }
}
