package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.User;
import org.odk.platform.domain.WebFormSession;
import org.odk.platform.repository.WebFormSessionRepository;
import org.odk.platform.security.CurrentUserService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WebFormAccessService {

    private final FormVersionService formVersionService;
    private final WebFormAssignmentService assignmentService;
    private final CurrentUserService currentUserService;
    private final WebFormSessionRepository sessionRepository;

    /**
     * Authorize creation/resume of a browser web-form session.
     *
     * Browser identity always comes from the authenticated web user. Any client
     * supplied userId must match that user and appUserId is rejected on this
     * browser-only endpoint so a caller cannot impersonate another principal.
     */
    public SessionIdentity authorizeStart(
            UUID formVersionId,
            UUID requestedUserId,
            UUID requestedAppUserId) {

        User currentUser = requireCurrentWebUser();

        if (requestedUserId != null && !requestedUserId.equals(currentUser.getId())) {
            throw new AccessDeniedException("Cannot start a web form session for another user");
        }

        if (requestedAppUserId != null) {
            throw new AccessDeniedException(
                    "App-user identity is not accepted by the browser web-form endpoint");
        }

        FormContext context = requireFormContext(formVersionId);
        requireFormAccess(context, currentUser.getId());

        return new SessionIdentity(currentUser.getId(), null);
    }

    /**
     * Authorize reading form-definition metadata before a session is started.
     */
    public void authorizeDefinition(UUID formVersionId) {
        User currentUser = requireCurrentWebUser();
        requireFormAccess(requireFormContext(formVersionId), currentUser.getId());
    }

    /**
     * Authorize every request that operates on an existing browser session.
     *
     * Project/system managers may administer project sessions. Other users must
     * own the session and still retain effective form access.
     */
    public void authorizeSession(UUID sessionId) {
        User currentUser = requireCurrentWebUser();

        WebFormSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Web form session not found: " + sessionId));

        FormContext context = requireFormContext(session.getFormVersion());

        if (hasSystemAdminAuthority()
                || assignmentService.canManageProjectForms(
                        context.projectId(),
                        currentUser.getId())) {
            return;
        }

        if (session.getUser() == null
                || session.getUser().getId() == null
                || !session.getUser().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("Web form session belongs to another user");
        }

        requireFormAccess(context, currentUser.getId());
    }

    private void requireFormAccess(FormContext context, UUID userId) {
        if (hasSystemAdminAuthority()) {
            return;
        }

        if (assignmentService.canManageProjectForms(context.projectId(), userId)) {
            return;
        }

        /*
         * Backwards-compatible rollout rule:
         *
         * A form that has never had web assignments configured remains available
         * to normal project participants. Once the first web assignment is ever
         * created, the form becomes assignment-restricted. Soft-removing the last
         * active assignment does NOT reopen it to everybody because inactive rows
         * still count as prior configuration.
         */
        if (!assignmentService.hasAssignmentConfiguration(
                context.projectId(),
                context.formId())) {

            if (assignmentService.isProjectParticipant(context.projectId(), userId)) {
                return;
            }

            throw new AccessDeniedException(
                    "User is not an active participant in this project");
        }

        if (!assignmentService.hasUserAccess(
                context.projectId(),
                context.formId(),
                userId)) {
            throw new AccessDeniedException(
                    "User is not assigned to this web form");
        }
    }

    private User requireCurrentWebUser() {
        return currentUserService.getCurrentWebUser()
                .orElseThrow(() -> new AccessDeniedException(
                        "Browser web forms require an authenticated web user"));
    }

    private boolean hasSystemAdminAuthority() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(authority ->
                        "ROLE_SYSTEM_ADMIN".equals(authority)
                                || "ROLE_ADMIN".equals(authority));
    }

    private FormContext requireFormContext(UUID formVersionId) {
        FormVersion formVersion = formVersionService.findById(formVersionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Form version not found: " + formVersionId));

        return requireFormContext(formVersion);
    }

    private FormContext requireFormContext(FormVersion formVersion) {
        if (formVersion == null
                || formVersion.getForm() == null
                || formVersion.getForm().getId() == null
                || formVersion.getForm().getProject() == null
                || formVersion.getForm().getProject().getId() == null) {
            throw new IllegalArgumentException(
                    "Form version is not attached to a valid project form");
        }

        return new FormContext(
                formVersion.getForm().getProject().getId(),
                formVersion.getForm().getId());
    }

    public record SessionIdentity(UUID userId, UUID appUserId) {
    }

    private record FormContext(UUID projectId, UUID formId) {
    }
}
