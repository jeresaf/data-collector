package org.odk.platform.openrosa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.User;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.XFormRepository;
import org.odk.platform.security.CurrentUserService;
import org.odk.platform.service.WebFormAssignmentService;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class EntityListSnapshotWebAuthorizationTest {
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID FORM = UUID.fromString("00000000-0000-0000-0000-000000000303");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000310");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000311");

    private CurrentUserService identity;
    private ProjectRepository projects;
    private XFormRepository forms;
    private WebFormAssignmentService memberships;
    private EntityListSnapshotWebAuthorization policy;
    private EntityListSnapshotLedgerCoordinator.Key key;

    @BeforeEach
    void setup() {
        identity = mock(CurrentUserService.class);
        projects = mock(ProjectRepository.class);
        forms = mock(XFormRepository.class);
        memberships = mock(WebFormAssignmentService.class);
        policy = new EntityListSnapshotWebAuthorization(identity, projects, forms, memberships);
        key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, "households.csv");

        var activeUser = mock(User.class);
        when(activeUser.getId()).thenReturn(ACTOR);
        when(activeUser.getIsActive()).thenReturn(true);
        when(identity.getCurrentWebUser()).thenReturn(Optional.of(activeUser));

        var activeProject = mock(Project.class);
        when(projects.findByIdAndIsActiveTrue(PROJECT)).thenReturn(Optional.of(activeProject));

        var activeForm = mock(XForm.class);
        when(activeForm.getIsActive()).thenReturn(true);
        when(forms.findByIdAndProjectId(FORM, PROJECT)).thenReturn(Optional.of(activeForm));

        when(memberships.canManageProjectForms(PROJECT, ACTOR)).thenReturn(true);
    }

    @Test
    void currentActiveWebManagerOfActiveProjectFormMayInitialize() {
        assertTrue(policy.mayInitialize(ACTOR, key));
        verify(memberships).canManageProjectForms(PROJECT, ACTOR);
    }

    @Test
    void spoofedActorIsDeniedBeforeAnyRepositoryReads() {
        assertFalse(policy.mayInitialize(OTHER, key));
        verifyNoInteractions(projects, forms, memberships);
    }

    @Test
    void missingOrInactiveWebIdentityFailsClosed() {
        when(identity.getCurrentWebUser()).thenReturn(Optional.empty());
        assertFalse(policy.mayInitialize(ACTOR, key));
        var inactive = mock(User.class);
        when(inactive.getId()).thenReturn(ACTOR);
        when(inactive.getIsActive()).thenReturn(false);
        when(identity.getCurrentWebUser()).thenReturn(Optional.of(inactive));
        assertFalse(policy.mayInitialize(ACTOR, key));
        verifyNoInteractions(projects, forms, memberships);
    }

    @Test
    void wrongProjectFormAndRevokedManagerRightsAreDenied() {
        when(projects.findByIdAndIsActiveTrue(PROJECT)).thenReturn(Optional.empty());
        assertFalse(policy.mayInitialize(ACTOR, key));

        when(projects.findByIdAndIsActiveTrue(PROJECT)).thenReturn(Optional.of(mock(Project.class)));
        when(forms.findByIdAndProjectId(FORM, PROJECT)).thenReturn(Optional.empty());
        assertFalse(policy.mayInitialize(ACTOR, key));

        var inactiveForm = mock(XForm.class);
        when(inactiveForm.getIsActive()).thenReturn(false);
        when(forms.findByIdAndProjectId(FORM, PROJECT)).thenReturn(Optional.of(inactiveForm));
        assertFalse(policy.mayInitialize(ACTOR, key));

        var activeForm = mock(XForm.class);
        when(activeForm.getIsActive()).thenReturn(true);
        when(forms.findByIdAndProjectId(FORM, PROJECT)).thenReturn(Optional.of(activeForm));
        when(memberships.canManageProjectForms(PROJECT, ACTOR)).thenReturn(false);
        assertFalse(policy.mayInitialize(ACTOR, key));
    }

    @Test
    void malformedOrUnscopedKeysAreRejected() {
        assertFalse(policy.mayInitialize(ACTOR, null));
        assertFalse(policy.mayInitialize(null, key));
        assertFalse(policy.mayInitialize(ACTOR,
                new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, "../outside.csv")));
        assertFalse(policy.mayInitialize(ACTOR,
                new EntityListSnapshotLedgerCoordinator.Key(PROJECT, null, "households.csv")));
    }
}
