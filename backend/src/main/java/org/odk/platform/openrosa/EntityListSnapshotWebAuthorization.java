package org.odk.platform.openrosa;

import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.XFormRepository;
import org.odk.platform.security.CurrentUserService;
import org.odk.platform.service.WebFormAssignmentService;

import java.util.Objects;
import java.util.UUID;

/**
 * Opt-in web-user policy adapter for first Entity List snapshot initialization.
 *
 * Not registered as a bean and never called from production routes. Only the
 * current authenticated web user's ID is accepted; mobile identities and
 * client-supplied IDs cannot impersonate a manager. This still requires a
 * transactionally rechecked permission and actor audit before activation.
 */
public final class EntityListSnapshotWebAuthorization
        implements JdbcEntityListSnapshotBootstrap.Authorization {

    private final CurrentUserService currentUserService;
    private final ProjectRepository projectRepository;
    private final XFormRepository formRepository;
    private final WebFormAssignmentService assignmentService;

    public EntityListSnapshotWebAuthorization(
            CurrentUserService currentUserService,
            ProjectRepository projectRepository,
            XFormRepository formRepository,
            WebFormAssignmentService assignmentService) {
        this.currentUserService = Objects.requireNonNull(currentUserService);
        this.projectRepository = Objects.requireNonNull(projectRepository);
        this.formRepository = Objects.requireNonNull(formRepository);
        this.assignmentService = Objects.requireNonNull(assignmentService);
    }

    @Override
    public boolean mayInitialize(UUID authenticatedActorId,
            EntityListSnapshotLedgerCoordinator.Key key) {
        if (authenticatedActorId == null || key == null
                || key.projectId() == null || key.formId() == null
                || !EntityListManifestPreflight.validTypedFilename(key.filename())) {
            return false;
        }
        var webUser = currentUserService.getCurrentWebUser();
        if (webUser.isEmpty()
                || webUser.get().getId() == null
                || !authenticatedActorId.equals(webUser.get().getId())
                || !Boolean.TRUE.equals(webUser.get().getIsActive())) {
            return false;
        }
        var project = projectRepository.findByIdAndIsActiveTrue(key.projectId());
        if (project.isEmpty()) {
            return false;
        }
        var form = formRepository.findByIdAndProjectId(key.formId(), key.projectId());
        if (form.isEmpty() || !Boolean.TRUE.equals(form.get().getIsActive())) {
            return false;
        }
        return assignmentService.canManageProjectForms(key.projectId(), authenticatedActorId);
    }
}
