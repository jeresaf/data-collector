package org.odk.platform.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.service.ProjectService;
import org.odk.platform.service.WebFormAssignmentService;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebFormAssignmentControllerTest {

    @Mock WebFormAssignmentService assignmentService;
    @Mock ProjectService projectService;
    @Mock AuditService auditService;

    @InjectMocks WebFormAssignmentController controller;

    @Test
    void assignUserAndRemoveEmitAssignmentAuditEvents() {
        UUID projectId = UUID.randomUUID();
        UUID formId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        WebFormAssignmentService.WebFormAssignmentView assignment =
                new WebFormAssignmentService.WebFormAssignmentView(
                        assignmentId,
                        projectId,
                        formId,
                        "USER",
                        userId,
                        "Alice Collector",
                        "alice@example.com",
                        Instant.now());

        WebFormAssignmentController.UserAssignmentRequest request =
                new WebFormAssignmentController.UserAssignmentRequest();
        request.setUserId(userId);

        when(projectService.getCurrentUserId()).thenReturn(actorId);
        when(assignmentService.assignUser(projectId, formId, userId, actorId))
                .thenReturn(assignment);
        when(assignmentService.findActiveAssignment(projectId, formId, assignmentId))
                .thenReturn(Optional.of(assignment));

        assertEquals(200, controller.assignUser(projectId, formId, request).getStatusCode().value());

        verify(auditService).log(
                eq(AuditActions.FORM_ASSIGNED),
                eq("FORM"),
                eq(formId),
                eq("Alice Collector"),
                anyMap());

        assertEquals(
                204,
                controller.removeAssignment(projectId, formId, assignmentId)
                        .getStatusCode().value());

        verify(assignmentService).removeAssignment(projectId, formId, assignmentId);
        verify(auditService).log(
                eq(AuditActions.FORM_UNASSIGNED),
                eq("FORM"),
                eq(formId),
                eq("Alice Collector"),
                anyMap());
    }

    @Test
    void assignRoleUsesRoleTarget() {
        UUID projectId = UUID.randomUUID();
        UUID formId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        WebFormAssignmentService.WebFormAssignmentView assignment =
                new WebFormAssignmentService.WebFormAssignmentView(
                        UUID.randomUUID(),
                        projectId,
                        formId,
                        "ROLE",
                        roleId,
                        "Data Analyst",
                        "DATA_ANALYST",
                        Instant.now());

        WebFormAssignmentController.RoleAssignmentRequest request =
                new WebFormAssignmentController.RoleAssignmentRequest();
        request.setRoleId(roleId);

        when(projectService.getCurrentUserId()).thenReturn(actorId);
        when(assignmentService.assignRole(projectId, formId, roleId, actorId))
                .thenReturn(assignment);

        assertEquals(200, controller.assignRole(projectId, formId, request).getStatusCode().value());

        verify(auditService).log(
                eq(AuditActions.FORM_ASSIGNED),
                eq("FORM"),
                eq(formId),
                eq("Data Analyst"),
                anyMap());
    }
}
