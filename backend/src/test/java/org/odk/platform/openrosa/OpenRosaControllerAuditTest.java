package org.odk.platform.openrosa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.AppUserProjectRepository;
import org.odk.platform.repository.FormAttachmentRepository;
import org.odk.platform.repository.FormExternalDatasetRepository;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.SubmissionRepository;
import org.odk.platform.repository.XFormRepository;
import org.odk.platform.security.CurrentUserService;
import org.odk.platform.service.OpenRosaSubmissionService;
import org.odk.platform.service.SubmissionAttachmentService;
import org.odk.platform.service.SubmissionReplacementService;
import org.odk.platform.service.storage.StorageServiceFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenRosaControllerAuditTest {

    @Mock ProjectRepository projectRepository;
    @Mock XFormRepository xFormRepository;
    @Mock FormVersionRepository formVersionRepository;
    @Mock FormAttachmentRepository formAttachmentRepository;
    @Mock FormExternalDatasetRepository formExternalDatasetRepository;
    @Mock SubmissionRepository submissionRepository;
    @Mock AppUserProjectRepository appUserProjectRepository;
    @Mock CurrentUserService currentUserService;
    @Mock StorageServiceFactory storageServiceFactory;
    @Mock SubmissionAttachmentService submissionAttachmentService;
    @Mock SubmissionReplacementService submissionReplacementService;
    @Mock AuditService auditService;
    @Mock OpenRosaSubmissionService openRosaSubmissionService;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;

    @InjectMocks OpenRosaController controller;

    private UUID projectId;
    private XForm form;
    private FormVersion formVersion;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        Project project = Project.builder().id(projectId).name("OpenRosa Audit").isActive(true).build();
        form = XForm.builder()
                .id(UUID.randomUUID())
                .project(project)
                .title("Audit Form")
                .xmlns("audit_form")
                .isPublished(true)
                .isActive(true)
                .build();
        formVersion = FormVersion.builder()
                .id(UUID.randomUUID())
                .form(form)
                .version("1")
                .xmlns("audit_form")
                .xmlContent("<data id=\"audit_form\"/>")
                .build();
    }

    @Test
    void formDownload_emitsAuditEvent() {
        when(xFormRepository.findByXmlnsAndProjectId("audit_form", projectId)).thenReturn(Optional.of(form));
        when(formVersionRepository.findLatestByFormId(form.getId())).thenReturn(Optional.of(formVersion));

        var result = controller.formDownload(projectId, "audit_form", request, response);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        verify(auditService).log(
                eq(AuditActions.OPENROSA_FORM_DOWNLOADED), eq("FORM"), eq(form.getId()), eq("Audit Form"), anyMap());
    }

    @Test
    void submissionPost_delegatesToSharedIntakeService() {
        String instanceId = "uuid:11111111-1111-1111-1111-111111111111";
        MockMultipartFile file = submissionFile(instanceId);
        Submission saved = Submission.builder().id(UUID.randomUUID()).instanceId(instanceId).build();
        when(openRosaSubmissionService.receive(eq(projectId), eq(file), any(), eq(request)))
                .thenReturn(new OpenRosaSubmissionService.Receipt(saved, instanceId, false, "ODK Collect"));

        var result = controller.submissionPost(
                projectId, file, null, new LinkedMultiValueMap<>(), request, response);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertTrue(result.getBody().contains("<status>201</status>"));
        assertTrue(result.getBody().contains(instanceId));
        verify(openRosaSubmissionService).receive(eq(projectId), eq(file), any(), eq(request));
    }

    @Test
    void submissionPost_preservesSharedServiceErrorStatus() {
        MockMultipartFile file = submissionFile("uuid:duplicate");
        when(openRosaSubmissionService.receive(eq(projectId), eq(file), any(), eq(request)))
                .thenThrow(new OpenRosaSubmissionService.IntakeException(
                        HttpStatus.CONFLICT, "Duplicate submission instanceID"));

        var result = controller.submissionPost(
                projectId, file, null, new LinkedMultiValueMap<>(), request, response);

        assertEquals(HttpStatus.CONFLICT, result.getStatusCode());
        assertTrue(result.getBody().contains("Duplicate submission instanceID"));
    }

    private MockMultipartFile submissionFile(String instanceId) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="audit_form" version="1">
                    <name>Audit</name>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """.formatted(instanceId);
        return new MockMultipartFile(
                "xml_submission_file", "submission.xml", MediaType.APPLICATION_XML_VALUE, xml.getBytes());
    }
}
