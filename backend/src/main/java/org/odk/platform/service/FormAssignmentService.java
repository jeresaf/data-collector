package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.AppUserProject;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.User;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.AppUserProjectRepository;
import org.odk.platform.repository.AppUserRepository;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.XFormRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FormAssignmentService {

    private final AppUserProjectRepository appUserProjectRepository;
    private final AppUserRepository appUserRepository;
    private final ProjectRepository projectRepository;
    private final XFormRepository xFormRepository;

    /**
     * Assign forms to an app user (mobile user) within a project.
     * This controls what forms the mobile user can download and submit.
     */
    @Transactional
    public List<AppUserProject> assignFormsToAppUser(
            UUID projectId,
            UUID appUserId,
            List<UUID> formIds,
            User assignedBy) {

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        AppUser appUser = appUserRepository.findById(appUserId)
                .orElseThrow(() -> new IllegalArgumentException("App user not found: " + appUserId));

        // Validate that all forms belong to the project
        List<XForm> forms = xFormRepository.findAllById(formIds);
        if (forms.size() != formIds.size()) {
            throw new IllegalArgumentException("One or more forms not found");
        }
        for (XForm form : forms) {
            if (!form.getProject().getId().equals(projectId)) {
                throw new IllegalArgumentException("Form " + form.getId() + " does not belong to project " + projectId);
            }
        }

        // Get existing assignments for this app user in this project
        List<AppUserProject> existingAssignments = appUserProjectRepository
                .findByAppUserIdAndProjectId(appUserId, projectId);

        // If no forms specified, remove all assignments
        if (formIds.isEmpty()) {
            appUserProjectRepository.deleteAll(existingAssignments);
            return List.of();
        }

        // Create a set of form IDs to assign
        Set<UUID> formIdSet = new HashSet<>(formIds);

        // Update existing assignments
        for (AppUserProject assignment : existingAssignments) {
            if (assignment.getForm() != null && formIdSet.contains(assignment.getForm().getId())) {
                // Keep this assignment, mark as active
                assignment.setStatus("ACTIVE");
                formIdSet.remove(assignment.getForm().getId());
            } else if (assignment.getForm() != null) {
                // Form no longer assigned, mark as inactive
                assignment.setStatus("INACTIVE");
            }
            appUserProjectRepository.save(assignment);
        }

        // Create new assignments for forms not already assigned
        List<AppUserProject> newAssignments = formIdSet.stream()
                .map(formId -> {
                    XForm form = forms.stream()
                            .filter(f -> f.getId().equals(formId))
                            .findFirst()
                            .orElseThrow();
                    return AppUserProject.builder()
                            .appUser(appUser)
                            .project(project)
                            .form(form)
                            .assignedBy(assignedBy)
                            .status("ACTIVE")
                            .build();
                })
                .collect(Collectors.toList());

        return appUserProjectRepository.saveAll(newAssignments);
    }

    /**
     * Get all form assignments for an app user in a project.
     */
    public List<AppUserProject> getAppUserFormAssignments(UUID projectId, UUID appUserId) {
        return appUserProjectRepository.findByAppUserIdAndProjectId(appUserId, projectId)
                .stream()
                .filter(aup -> aup.getForm() != null)
                .collect(Collectors.toList());
    }

    /**
     * Get all active form assignments for an app user in a project.
     */
    public List<AppUserProject> getActiveAppUserFormAssignments(UUID projectId, UUID appUserId) {
        return appUserProjectRepository.findByAppUserIdAndProjectId(appUserId, projectId)
                .stream()
                .filter(aup -> aup.getForm() != null && "ACTIVE".equals(aup.getStatus()))
                .collect(Collectors.toList());
    }

    /**
     * Get all app users assigned to a specific form in a project.
     */
    public List<AppUserProject> getFormAssignments(UUID projectId, UUID formId) {
        return appUserProjectRepository.findByFormId(formId)
                .stream()
                .filter(aup -> aup.getProject().getId().equals(projectId))
                .collect(Collectors.toList());
    }

    /**
     * Remove a specific form assignment for an app user.
     */
    @Transactional
    public void removeFormAssignment(UUID projectId, UUID appUserId, UUID formId) {
        List<AppUserProject> assignments = appUserProjectRepository.findByAppUserIdAndProjectId(appUserId, projectId);
        for (AppUserProject assignment : assignments) {
            if (assignment.getForm() != null && assignment.getForm().getId().equals(formId)) {
                assignment.setStatus("INACTIVE");
                appUserProjectRepository.save(assignment);
            }
        }
    }

    /**
     * Remove all form assignments for an app user in a project.
     */
    @Transactional
    public void removeAllFormAssignments(UUID projectId, UUID appUserId) {
        List<AppUserProject> assignments = appUserProjectRepository.findByAppUserIdAndProjectId(appUserId, projectId);
        appUserProjectRepository.deleteAll(assignments);
    }

    /**
     * Check if an app user has access to a specific form.
     */
    public boolean hasFormAccess(UUID appUserId, UUID projectId, UUID formId) {
        return appUserProjectRepository.findByAppUserIdAndProjectId(appUserId, projectId)
                .stream()
                .anyMatch(aup -> aup.getForm() != null
                        && aup.getForm().getId().equals(formId)
                        && "ACTIVE".equals(aup.getStatus()));
    }

    /**
     * Get all form IDs an app user has access to in a project.
     */
    public List<UUID> getAssignedFormIds(UUID appUserId, UUID projectId) {
        return appUserProjectRepository.findAssignedFormIdsByAppUserIdAndProjectId(appUserId, projectId);
    }
}