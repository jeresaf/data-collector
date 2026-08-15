package org.odk.platform.controller;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.service.ProjectService;
import org.odk.platform.service.WebFormAssignmentService;
import org.odk.platform.service.WebFormAssignmentService.RoleCandidate;
import org.odk.platform.service.WebFormAssignmentService.UserCandidate;
import org.odk.platform.service.WebFormAssignmentService.WebFormAssignmentView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/forms/{formId}/web-assignments")
@RequiredArgsConstructor
public class WebFormAssignmentController {

    private static final String MANAGE =
            "hasRole('ADMIN') or hasRole('SYSTEM_ADMIN') or "
                    + "hasRole('PROJECT_ADMIN') or hasRole('PROJECT_MANAGER')";

    private final WebFormAssignmentService assignmentService;
    private final ProjectService projectService;
    private final AuditService auditService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<WebFormAssignmentView>> listAssignments(
            @PathVariable UUID projectId,
            @PathVariable UUID formId) {
        return ResponseEntity.ok(assignmentService.listAssignments(projectId, formId));
    }

    @GetMapping("/users")
    @PreAuthorize(MANAGE)
    public ResponseEntity<List<UserCandidate>> searchUsers(
            @PathVariable UUID projectId,
            @PathVariable UUID formId,
            @RequestParam(name = "q", defaultValue = "") String query) {
        return ResponseEntity.ok(
                assignmentService.searchUserCandidates(projectId, formId, query));
    }

    @GetMapping("/roles")
    @PreAuthorize(MANAGE)
    public ResponseEntity<List<RoleCandidate>> listRoles(
            @PathVariable UUID projectId,
            @PathVariable UUID formId) {
        return ResponseEntity.ok(
                assignmentService.listRoleCandidates(projectId, formId));
    }

    @PostMapping("/users")
    @PreAuthorize(MANAGE)
    public ResponseEntity<WebFormAssignmentView> assignUser(
            @PathVariable UUID projectId,
            @PathVariable UUID formId,
            @RequestBody UserAssignmentRequest request) {

        UUID actorId = projectService.getCurrentUserId();
        WebFormAssignmentView assignment = assignmentService.assignUser(
                projectId,
                formId,
                request.getUserId(),
                actorId);

        auditAssignment(AuditActions.FORM_ASSIGNED, assignment);
        return ResponseEntity.ok(assignment);
    }

    @PostMapping("/roles")
    @PreAuthorize(MANAGE)
    public ResponseEntity<WebFormAssignmentView> assignRole(
            @PathVariable UUID projectId,
            @PathVariable UUID formId,
            @RequestBody RoleAssignmentRequest request) {

        UUID actorId = projectService.getCurrentUserId();
        WebFormAssignmentView assignment = assignmentService.assignRole(
                projectId,
                formId,
                request.getRoleId(),
                actorId);

        auditAssignment(AuditActions.FORM_ASSIGNED, assignment);
        return ResponseEntity.ok(assignment);
    }

    @DeleteMapping("/{assignmentId}")
    @PreAuthorize(MANAGE)
    public ResponseEntity<Void> removeAssignment(
            @PathVariable UUID projectId,
            @PathVariable UUID formId,
            @PathVariable UUID assignmentId) {

        WebFormAssignmentView assignment = assignmentService
                .findActiveAssignment(projectId, formId, assignmentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Active web form assignment not found: " + assignmentId));

        assignmentService.removeAssignment(projectId, formId, assignmentId);
        auditAssignment(AuditActions.FORM_UNASSIGNED, assignment);
        return ResponseEntity.noContent().build();
    }

    private void auditAssignment(String action, WebFormAssignmentView assignment) {
        auditService.log(
                action,
                "FORM",
                assignment.formId(),
                assignment.targetName(),
                Map.of(
                        "projectId", assignment.projectId().toString(),
                        "formId", assignment.formId().toString(),
                        "assignmentId", assignment.id().toString(),
                        "targetType", assignment.targetType(),
                        "targetId", assignment.targetId().toString(),
                        "targetName", assignment.targetName()
                )
        );
    }

    @Data
    public static class UserAssignmentRequest {
        private UUID userId;
    }

    @Data
    public static class RoleAssignmentRequest {
        private UUID roleId;
    }
}
