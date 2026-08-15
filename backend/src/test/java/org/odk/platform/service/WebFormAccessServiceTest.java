package org.odk.platform.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.User;
import org.odk.platform.domain.WebFormSession;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.WebFormSessionRepository;
import org.odk.platform.security.CurrentUserService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebFormAccessServiceTest {

    @Mock FormVersionService formVersionService;
    @Mock WebFormAssignmentService assignmentService;
    @Mock CurrentUserService currentUserService;
    @Mock WebFormSessionRepository sessionRepository;

    private WebFormAccessService service;

    private UUID projectId;
    private UUID formId;
    private UUID versionId;
    private UUID userId;
    private User user;
    private FormVersion formVersion;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        service = new WebFormAccessService(
                formVersionService,
                assignmentService,
                currentUserService,
                sessionRepository);

        projectId = UUID.randomUUID();
        formId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        userId = UUID.randomUUID();

        user = User.builder()
                .id(userId)
                .username("collector")
                .email("collector@example.com")
                .isActive(true)
                .build();

        Project project = Project.builder()
                .id(projectId)
                .name("Access Project")
                .build();

        XForm form = XForm.builder()
                .id(formId)
                .project(project)
                .title("Access Form")
                .build();

        formVersion = FormVersion.builder()
                .id(versionId)
                .form(form)
                .version("1")
                .xmlContent("<data id=\"access\"/>")
                .build();

        when(currentUserService.getCurrentWebUser()).thenReturn(Optional.of(user));
        when(formVersionService.findById(versionId)).thenReturn(Optional.of(formVersion));

        authenticate("collector", "ROLE_DATA_COLLECTOR");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unconfiguredFormAllowsActiveProjectParticipant() {
        when(assignmentService.canManageProjectForms(projectId, userId)).thenReturn(false);
        when(assignmentService.hasAssignmentConfiguration(projectId, formId)).thenReturn(false);
        when(assignmentService.isProjectParticipant(projectId, userId)).thenReturn(true);

        WebFormAccessService.SessionIdentity identity =
                service.authorizeStart(versionId, null, null);

        assertEquals(userId, identity.userId());
        assertNull(identity.appUserId());
        verify(assignmentService, never()).hasUserAccess(any(), any(), any());
    }

    @Test
    void configuredFormRequiresDirectOrRoleAssignment() {
        when(assignmentService.canManageProjectForms(projectId, userId)).thenReturn(false);
        when(assignmentService.hasAssignmentConfiguration(projectId, formId)).thenReturn(true);
        when(assignmentService.hasUserAccess(projectId, formId, userId)).thenReturn(true);

        assertDoesNotThrow(() -> service.authorizeStart(versionId, userId, null));
    }

    @Test
    void configuredFormRejectsUnassignedUser() {
        when(assignmentService.canManageProjectForms(projectId, userId)).thenReturn(false);
        when(assignmentService.hasAssignmentConfiguration(projectId, formId)).thenReturn(true);
        when(assignmentService.hasUserAccess(projectId, formId, userId)).thenReturn(false);

        AccessDeniedException error = assertThrows(
                AccessDeniedException.class,
                () -> service.authorizeStart(versionId, userId, null));

        assertTrue(error.getMessage().contains("not assigned"));
    }

    @Test
    void projectManagerBypassesFormAssignment() {
        when(assignmentService.canManageProjectForms(projectId, userId)).thenReturn(true);

        assertDoesNotThrow(() -> service.authorizeStart(versionId, userId, null));

        verify(assignmentService, never()).hasAssignmentConfiguration(any(), any());
        verify(assignmentService, never()).hasUserAccess(any(), any(), any());
    }

    @Test
    void systemAdminBypassesProjectAndFormAssignment() {
        authenticate("collector", "ROLE_SYSTEM_ADMIN");

        assertDoesNotThrow(() -> service.authorizeStart(versionId, null, null));

        verifyNoInteractions(assignmentService);
    }

    @Test
    void requestCannotImpersonateAnotherWebUserOrAppUser() {
        UUID otherUser = UUID.randomUUID();

        assertThrows(
                AccessDeniedException.class,
                () -> service.authorizeStart(versionId, otherUser, null));

        assertThrows(
                AccessDeniedException.class,
                () -> service.authorizeStart(versionId, userId, UUID.randomUUID()));
    }

    @Test
    void existingSessionRequiresOwnershipForOrdinaryUser() {
        User other = User.builder()
                .id(UUID.randomUUID())
                .username("other")
                .email("other@example.com")
                .build();

        WebFormSession session = WebFormSession.builder()
                .id(UUID.randomUUID())
                .formVersion(formVersion)
                .user(other)
                .status("draft")
                .build();

        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(assignmentService.canManageProjectForms(projectId, userId)).thenReturn(false);

        assertThrows(
                AccessDeniedException.class,
                () -> service.authorizeSession(session.getId()));
    }

    @Test
    void existingOwnedSessionRechecksConfiguredFormAccess() {
        WebFormSession session = WebFormSession.builder()
                .id(UUID.randomUUID())
                .formVersion(formVersion)
                .user(user)
                .status("draft")
                .build();

        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(assignmentService.canManageProjectForms(projectId, userId)).thenReturn(false);
        when(assignmentService.hasAssignmentConfiguration(projectId, formId)).thenReturn(true);
        when(assignmentService.hasUserAccess(projectId, formId, userId)).thenReturn(true);

        assertDoesNotThrow(() -> service.authorizeSession(session.getId()));
    }

    private void authenticate(String username, String... authorities) {
        var granted = List.of(authorities).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        var principal = org.springframework.security.core.userdetails.User.builder()
                .username(username)
                .password("ignored")
                .authorities(granted)
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()));
    }
}
