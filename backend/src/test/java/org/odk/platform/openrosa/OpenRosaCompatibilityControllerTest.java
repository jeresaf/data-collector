package org.odk.platform.openrosa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.*;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.SubmissionRepository;
import org.odk.platform.repository.XFormRepository;
import org.odk.platform.security.CurrentUserService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenRosaCompatibilityControllerTest {
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final XFormRepository forms = mock(XFormRepository.class);
    private final FormVersionRepository versions = mock(FormVersionRepository.class);
    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final AuditService audit = mock(AuditService.class);
    private OpenRosaCompatibilityController controller;

    @BeforeEach
    void setUp() {
        controller = new OpenRosaCompatibilityController();
        ReflectionTestUtils.setField(controller, "projectRepository", projects);
        ReflectionTestUtils.setField(controller, "xFormRepository", forms);
        ReflectionTestUtils.setField(controller, "formVersionRepository", versions);
        ReflectionTestUtils.setField(controller, "submissionRepository", submissions);
        ReflectionTestUtils.setField(controller, "currentUserService", currentUser);
        ReflectionTestUtils.setField(controller, "auditService", audit);
    }

    @Test
    void formListWithoutProjectContextReturnsOpenRosaErrorAndHeaders() {
        when(currentUser.getCurrentUser()).thenReturn(Optional.empty());
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        var response = controller.compatibilityFormList(new MockHttpServletRequest(), servletResponse);
        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().contains("No project context"));
        assertEquals("1.0", servletResponse.getHeader("X-OpenRosa-Version"));
    }

    @Test
    void formListUsesActiveAppUserProjectAndIncludesMd5Hash() {
        UUID projectId = UUID.randomUUID(), formId = UUID.randomUUID(), versionId = UUID.randomUUID();
        Project project = Project.builder().id(projectId).isActive(true).build();
        AppUser appUser = AppUser.builder().id(UUID.randomUUID()).username("collector").build();
        AppUserProject assignment = AppUserProject.builder()
                .appUser(appUser).project(project).status("ACTIVE").build();
        appUser.setAppUserProjects(Set.of(assignment));
        XForm form = XForm.builder().id(formId).project(project).title("A & B").isPublished(true).isActive(true).build();
        FormVersion version = FormVersion.builder()
                .id(versionId).form(form).version("1").xmlns("demo").xmlContent("<data id=\"demo\"/>").build();
        when(currentUser.getCurrentUser()).thenReturn(Optional.of((org.odk.platform.domain.UserPrincipal) appUser));
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(forms.findPublishedByProjectId(projectId)).thenReturn(List.of(form));
        when(versions.findLatestByFormId(formId)).thenReturn(Optional.of(version));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("example.test");
        request.setServerPort(443);
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        var response = controller.compatibilityFormList(request, servletResponse);
        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody();
        assertTrue(body.contains("<name>A &amp; B</name>"));
        assertTrue(body.contains("<hash>md5:"));
        assertTrue(body.contains("https://example.test/v1/projects/" + projectId + "/forms/demo.xml"));
    }
}
