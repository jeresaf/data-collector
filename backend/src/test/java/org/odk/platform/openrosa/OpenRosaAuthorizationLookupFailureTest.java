package org.odk.platform.openrosa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.XFormRepository;
import org.odk.platform.security.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression for U01-T03: a transient/revoked identity lookup must never
 * downgrade an OpenRosa read to unrestricted project form/media access.
 * The controller is exercised directly to isolate its failure behavior from
 * the servlet security filter (which is qualified in HTTP integration tests).
 */
class OpenRosaAuthorizationLookupFailureTest {

    private static final UUID PROJECT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000042");

    private OpenRosaController controller;
    private CurrentUserService currentUserService;

    @BeforeEach
    void setUp() {
        controller = new OpenRosaController();
        currentUserService = mock(CurrentUserService.class);

        ProjectRepository projects = mock(ProjectRepository.class);
        Project activeProject = mock(Project.class);
        when(activeProject.getIsActive()).thenReturn(true);
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(activeProject));

        XFormRepository forms = mock(XFormRepository.class);
        XForm publishedForm = mock(XForm.class);
        when(publishedForm.getIsPublished()).thenReturn(true);
        when(publishedForm.getIsActive()).thenReturn(true);
        when(forms.findByXmlnsAndProjectId("sample_form", PROJECT_ID))
                .thenReturn(Optional.of(publishedForm));

        ReflectionTestUtils.setField(controller, "projectRepository", projects);
        ReflectionTestUtils.setField(controller, "xFormRepository", forms);
        ReflectionTestUtils.setField(controller, "currentUserService", currentUserService);

        when(currentUserService.getCurrentUser())
                .thenThrow(new IllegalStateException("synthetic identity lookup failure"));
    }

    @Test
    void formListMustFailClosedWhenIdentityLookupThrows() {
        assertEquals(HttpStatus.FORBIDDEN, controller.formList(PROJECT_ID,
                new MockHttpServletRequest(), new MockHttpServletResponse()).getStatusCode());
    }

    @Test
    void formXmlMustFailClosedWhenIdentityLookupThrows() {
        assertEquals(HttpStatus.FORBIDDEN, controller.formDownload(PROJECT_ID, "sample_form",
                new MockHttpServletRequest(), new MockHttpServletResponse()).getStatusCode());
    }

    @Test
    void manifestMustFailClosedWhenIdentityLookupThrows() {
        assertEquals(HttpStatus.FORBIDDEN, controller.formManifest(PROJECT_ID, "sample_form",
                new MockHttpServletRequest(), new MockHttpServletResponse()).getStatusCode());
    }

    @Test
    void attachmentMustFailClosedWhenIdentityLookupThrows() {
        assertEquals(HttpStatus.FORBIDDEN, controller.attachmentDownload(
                PROJECT_ID, "sample_form", "choices.csv",
                new MockHttpServletRequest(), new MockHttpServletResponse()).getStatusCode());
    }
}
