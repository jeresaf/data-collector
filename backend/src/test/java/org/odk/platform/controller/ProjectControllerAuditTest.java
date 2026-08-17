package org.odk.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javarosa.core.model.FormDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.AppUserProject;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.User;
import org.odk.platform.domain.XForm;
import org.odk.platform.service.FormAssignmentService;
import org.odk.platform.service.FormMetadataExtractor;
import org.odk.platform.service.FormPublishHistoryService;
import org.odk.platform.service.FormVersionService;
import org.odk.platform.service.JavaRosaFormLoader;
import org.odk.platform.service.JavaRosaFormValidator;
import org.odk.platform.service.ProjectService;
import org.odk.platform.service.XFormService;
import org.odk.platform.service.XlsFormImportService;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectControllerAuditTest {

    @Mock ProjectService projectService;
    @Mock AuditService auditService;
    @Mock FormAssignmentService formAssignmentService;
    @Mock FormMetadataExtractor formMetadataExtractor;
    @Mock FormPublishHistoryService formPublishHistoryService;
    @Mock FormVersionService formVersionService;
    @Mock JavaRosaFormValidator javaRosaFormValidator;
    @Mock JavaRosaFormLoader javaRosaFormLoader;
    @Mock XFormService xFormService;
    @Mock XlsFormImportService xlsFormImportService;

    @InjectMocks ProjectController controller;

    private UUID projectId;
    private User admin;
    private Project project;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        admin = User.builder().id(UUID.randomUUID()).username("admin").build();
        project = Project.builder().id(projectId).name("Audit Project").owner(admin).createdBy(admin).build();
        when(projectService.getCurrentUser()).thenReturn(admin);
    }

    @Test
    void xmlUpload_emitsValidationAndUploadEvents() {
        when(projectService.findById(projectId)).thenReturn(Optional.of(project));

        FormDef formDef = mock(FormDef.class);
        when(javaRosaFormLoader.loadFormDef(anyString())).thenReturn(formDef);
        when(javaRosaFormLoader.getFormId(formDef)).thenReturn("audit_form");
        when(javaRosaFormLoader.getFormVersion(formDef)).thenReturn("1");
        when(javaRosaFormLoader.getFormTitle(formDef)).thenReturn("Audit Form");
        when(javaRosaFormLoader.generateFormHash(anyString())).thenReturn("abc123");
        when(xFormService.findByXmlnsAndProjectId("audit_form", projectId)).thenReturn(Optional.empty());
        when(xFormService.save(any(XForm.class))).thenAnswer(invocation -> {
            XForm form = invocation.getArgument(0);
            if (form.getId() == null) form.setId(UUID.randomUUID());
            return form;
        });
        when(formVersionService.save(any(FormVersion.class))).thenAnswer(invocation -> {
            FormVersion version = invocation.getArgument(0);
            if (version.getId() == null) version.setId(UUID.randomUUID());
            return version;
        });
        doThrow(new RuntimeException("metadata not relevant to audit test"))
                .when(formMetadataExtractor).extractMetadata(eq(formDef), anyString());

        MockMultipartFile file = new MockMultipartFile(
                "file", "audit.xml", MediaType.APPLICATION_XML_VALUE,
                "<data/>".getBytes());

        var response = controller.uploadFormXml(projectId, file);
        assertEquals(200, response.getStatusCode().value());

        verify(auditService).log(eq(AuditActions.FORM_VALIDATED), eq("FORM"), any(UUID.class), eq("Audit Form"), anyMap());
        verify(auditService).log(eq(AuditActions.FORM_UPLOADED), eq("FORM"), any(UUID.class), eq("Audit Form"), anyMap());
    }


    @Test
    void validationAndXlsFormUpload_emitAuditEvents() {
        when(javaRosaFormValidator.validate(anyString())).thenReturn(true);
        FormDef formDef = mock(FormDef.class);
        when(javaRosaFormLoader.loadFormDef(anyString())).thenReturn(formDef);
        when(javaRosaFormLoader.getFormId(formDef)).thenReturn("validated_form");
        when(javaRosaFormLoader.getFormVersion(formDef)).thenReturn("2");
        when(javaRosaFormLoader.getFormTitle(formDef)).thenReturn("Validated Form");
        when(javaRosaFormLoader.getInstanceRootNode(formDef)).thenReturn("data");
        when(javaRosaFormLoader.generateFormHash(anyString())).thenReturn("hash");

        MockMultipartFile xml = new MockMultipartFile(
                "file", "validate.xml", MediaType.APPLICATION_XML_VALUE, "<data/>".getBytes());
        assertEquals(200, controller.validateFormXml(projectId, xml).getStatusCode().value());
        verify(auditService).log(
                eq(AuditActions.FORM_VALIDATED), eq("FORM"), isNull(), eq("Validated Form"), anyMap());

        when(projectService.findById(projectId)).thenReturn(Optional.of(project));
        UUID formId = UUID.randomUUID();
        UUID formVersionId = UUID.randomUUID();
        XlsFormImportService.ImportResult result = XlsFormImportService.ImportResult.builder()
                .formId(formId)
                .formVersionId(formVersionId)
                .formHash("xls-hash")
                .xmlns("xls_audit")
                .version("3")
                .title("XLS Audit")
                .build();
        when(xlsFormImportService.importWorkbook(
                eq(project), any(byte[].class), eq("audit.xlsx"), isNull(), isNull(), eq(admin)))
                .thenReturn(result);

        MockMultipartFile xlsx = new MockMultipartFile(
                "file", "audit.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[]{1, 2, 3});
        assertEquals(200, controller.uploadXLSForm(projectId, xlsx, null, null).getStatusCode().value());
        verify(auditService).log(
                eq(AuditActions.FORM_VALIDATED), eq("FORM"), eq(formId), eq("XLS Audit"), anyMap());
        verify(auditService).log(
                eq(AuditActions.FORM_UPLOADED), eq("FORM"), eq(formId), eq("XLS Audit"), anyMap());
    }

    @Test
    void publishAndAssignment_emitLifecycleEvents() {
        UUID formId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID appUserId = UUID.randomUUID();

        XForm form = XForm.builder()
                .id(formId)
                .project(project)
                .title("Audit Form")
                .xmlns("audit_form")
                .build();
        FormVersion version = FormVersion.builder()
                .id(versionId)
                .form(form)
                .version("1")
                .isPublished(false)
                .build();

        when(formVersionService.findById(versionId)).thenReturn(Optional.of(version));
        when(formVersionService.findByFormIdOrderByVersionDesc(formId)).thenReturn(List.of(version));
        when(xFormService.save(any(XForm.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var publishResponse = controller.publishForm(projectId, formId, versionId);
        assertEquals(200, publishResponse.getStatusCode().value());

        ProjectController.PublishFormResponse publishBody = publishResponse.getBody();
        assertNotNull(publishBody);
        assertEquals(formId, publishBody.getFormId());
        assertEquals(versionId, publishBody.getFormVersionId());
        assertEquals("1", publishBody.getVersion());
        assertEquals(true, publishBody.isPublished());
        assertEquals(true, publishBody.isLatest());
        assertDoesNotThrow(() -> new ObjectMapper().findAndRegisterModules().writeValueAsString(publishBody));

        verify(formPublishHistoryService).recordPublish(eq(version), eq(admin), contains("Published version 1"));
        verify(auditService).log(eq(AuditActions.FORM_PUBLISHED), eq("FORM"), eq(formId), eq("Audit Form"), anyMap());

        when(xFormService.findByIdAndProjectId(formId, projectId)).thenReturn(Optional.of(form));
        assertEquals(200, controller.archiveForm(projectId, formId).getStatusCode().value());
        verify(formPublishHistoryService).recordArchive(eq(version), eq(admin), contains("Archived form"));

        ProjectController.AssignFormsRequest request = ProjectController.AssignFormsRequest.builder()
                .formIds(List.of(formId))
                .build();
        AppUserProject assignment = mock(AppUserProject.class);
        when(formAssignmentService.assignFormsToAppUser(projectId, appUserId, List.of(formId), admin))
                .thenReturn(List.of(assignment));

        assertEquals(200, controller.assignFormsToAppUser(projectId, appUserId, request).getStatusCode().value());
        verify(auditService).log(eq(AuditActions.FORM_ASSIGNED), eq("APP_USER"), eq(appUserId), eq(appUserId.toString()), anyMap());

        assertEquals(200, controller.removeFormAssignment(projectId, appUserId, formId).getStatusCode().value());
        verify(auditService).log(eq(AuditActions.FORM_UNASSIGNED), eq("APP_USER"), eq(appUserId), eq(appUserId.toString()), anyMap());
    }
}
