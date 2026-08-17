package org.odk.platform.controller;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.User;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.AppUserRepository;
import org.odk.platform.repository.XFormRepository;
import org.odk.platform.service.FormAssignmentService;
import org.odk.platform.service.ProjectService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTO-based administration facade for mobile App User -> form assignments.
 *
 * The existing ProjectController assignment API remains available for mobile
 * clients and integrations. This controller gives the web administration UI a
 * bounded response model so it never has to serialize AppUserProject JPA
 * graphs or depend on the system-only Identity Administration endpoints.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/mobile-form-assignments")
@RequiredArgsConstructor
public class MobileFormAssignmentController {

    private static final String MANAGE_ROLES =
            "hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or "
                    + "hasRole('PROJECT_ADMIN') or hasRole('PROJECT_MANAGER')";

    private final FormAssignmentService formAssignmentService;
    private final AppUserRepository appUserRepository;
    private final XFormRepository xFormRepository;
    private final ProjectService projectService;
    private final AuditService auditService;

    @GetMapping("/{formId}")
    @PreAuthorize(MANAGE_ROLES)
    public ResponseEntity<List<MobileAssignmentUserResponse>> listCandidates(
            @PathVariable UUID projectId,
            @PathVariable UUID formId) {

        requireProjectForm(projectId, formId);

        List<MobileAssignmentUserResponse> users = appUserRepository.findByIsActiveTrue().stream()
                .map(appUser -> toResponse(
                        appUser,
                        formAssignmentService.hasFormAccess(appUser.getId(), projectId, formId)))
                .sorted(Comparator.comparing(
                        candidate -> candidate.getFullName() != null
                                ? candidate.getFullName().toLowerCase()
                                : candidate.getUsername().toLowerCase()))
                .toList();

        return ResponseEntity.ok(users);
    }

    @PostMapping("/{formId}/app-users/{appUserId}")
    @PreAuthorize(MANAGE_ROLES)
    public ResponseEntity<MobileAssignmentUserResponse> assign(
            @PathVariable UUID projectId,
            @PathVariable UUID formId,
            @PathVariable UUID appUserId) {

        requireProjectForm(projectId, formId);
        AppUser appUser = requireActiveAppUser(appUserId);

        List<UUID> assignedFormIds = new ArrayList<>(
                formAssignmentService.getAssignedFormIds(appUserId, projectId));
        if (!assignedFormIds.contains(formId)) {
            assignedFormIds.add(formId);
        }

        User currentUser = projectService.getCurrentUser();
        formAssignmentService.assignFormsToAppUser(
                projectId,
                appUserId,
                assignedFormIds,
                currentUser);

        auditService.log(
                AuditActions.FORM_ASSIGNED,
                "APP_USER",
                appUserId,
                appUser.getUsername(),
                Map.of("projectId", projectId, "formId", formId, "source", "WEB_ADMIN"));

        return ResponseEntity.ok(toResponse(appUser, true));
    }

    @DeleteMapping("/{formId}/app-users/{appUserId}")
    @PreAuthorize(MANAGE_ROLES)
    public ResponseEntity<MobileAssignmentUserResponse> remove(
            @PathVariable UUID projectId,
            @PathVariable UUID formId,
            @PathVariable UUID appUserId) {

        requireProjectForm(projectId, formId);
        AppUser appUser = appUserRepository.findById(appUserId)
                .orElseThrow(() -> new IllegalArgumentException("App user not found: " + appUserId));

        formAssignmentService.removeFormAssignment(projectId, appUserId, formId);

        auditService.log(
                AuditActions.FORM_UNASSIGNED,
                "APP_USER",
                appUserId,
                appUser.getUsername(),
                Map.of("projectId", projectId, "formId", formId, "source", "WEB_ADMIN"));

        return ResponseEntity.ok(toResponse(appUser, false));
    }

    private XForm requireProjectForm(UUID projectId, UUID formId) {
        XForm form = xFormRepository.findById(formId)
                .orElseThrow(() -> new IllegalArgumentException("Form not found: " + formId));
        if (form.getProject() == null || !projectId.equals(form.getProject().getId())) {
            throw new IllegalArgumentException(
                    "Form " + formId + " does not belong to project " + projectId);
        }
        return form;
    }

    private AppUser requireActiveAppUser(UUID appUserId) {
        AppUser appUser = appUserRepository.findById(appUserId)
                .orElseThrow(() -> new IllegalArgumentException("App user not found: " + appUserId));
        if (!appUser.isActive()) {
            throw new IllegalArgumentException("App user is inactive: " + appUserId);
        }
        return appUser;
    }

    private MobileAssignmentUserResponse toResponse(AppUser appUser, boolean assigned) {
        return MobileAssignmentUserResponse.builder()
                .id(appUser.getId())
                .username(appUser.getUsername())
                .email(appUser.getEmail())
                .fullName(appUser.getFullName())
                .verified(appUser.isVerified())
                .assigned(assigned)
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MobileAssignmentUserResponse {
        private UUID id;
        private String username;
        private String email;
        private String fullName;
        private boolean verified;
        private boolean assigned;
    }
}
