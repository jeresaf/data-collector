package org.odk.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.javarosa.core.model.FormDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.UserPrincipal;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.AppUserProjectRepository;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.SubmissionRepository;
import org.odk.platform.repository.XFormRepository;
import org.odk.platform.security.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenRosaSubmissionServiceTest {

    @Mock ProjectRepository projects;
    @Mock XFormRepository forms;
    @Mock FormVersionRepository versions;
    @Mock SubmissionRepository submissions;
    @Mock AppUserProjectRepository assignments;
    @Mock CurrentUserService currentUser;
    @Mock FormExternalDatasetService datasets;
    @Mock EntityListDatasetService entityDatasets;
    @Mock SubmissionXmlParser parser;
    @Mock SubmissionProcessingService processing;
    @Mock SubmissionPersistenceService persistence;
    @Mock SubmissionAttachmentService attachments;
    @Mock SubmissionReplacementService replacements;
    @Mock RecordWorkService recordWork;
    @Mock AuditService audit;
    @Mock HttpServletRequest request;

    @InjectMocks OpenRosaSubmissionService service;

    private UUID projectId;
    private Project project;
    private XForm form;
    private FormVersion version;
    private AppUser appUser;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        project = Project.builder().id(projectId).isActive(true).build();
        form = XForm.builder()
                .id(UUID.randomUUID()).project(project).xmlns("demo")
                .title("Demo").isPublished(true).isActive(true).build();
        version = FormVersion.builder()
                .id(UUID.randomUUID()).form(form).version("1").xmlns("demo")
                .xmlContent("<h:html/>").build();
        appUser = AppUser.builder().id(UUID.randomUUID()).username("collector").build();

        lenient().when(projects.findById(projectId)).thenReturn(Optional.of(project));
        lenient().when(forms.findByXmlnsAndProjectId("demo", projectId)).thenReturn(Optional.of(form));
        lenient().when(versions.findByFormIdAndVersion(form.getId(), "1")).thenReturn(Optional.of(version));
        lenient().when(currentUser.getCurrentUser()).thenReturn(Optional.of((UserPrincipal) appUser));
        lenient().when(assignments.findAssignedFormIdsByAppUserIdAndProjectId(appUser.getId(), projectId))
                .thenReturn(List.of(form.getId()));
        lenient().when(entityDatasets.loadReferencedEntityDatasets(version))
                .thenReturn(new EntityListDatasetService.DatasetBundle(Map.of(), List.of()));
        lenient().when(request.getHeader("User-Agent")).thenReturn("KoboCollect/2026");
        lenient().when(request.getHeader("X-OpenRosa-Version")).thenReturn("1.0");
    }

    @Test
    void receive_usesValidatedProcessingAndNormalizedPersistenceForAllMultipartData() throws Exception {
        String instanceId = "uuid:one";
        String xml = "<data id=\"demo\" version=\"1\"><name>A</name><meta><instanceID>"
                + instanceId + "</instanceID></meta></data>";
        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file", "submission.xml", "text/xml", xml.getBytes());
        MockMultipartFile photo = new MockMultipartFile(
                "photo", "photo.jpg", "image/jpeg", new byte[]{1, 2});
        LinkedMultiValueMap<String, org.springframework.web.multipart.MultipartFile> files = new LinkedMultiValueMap<>();
        files.add("photo", photo);

        when(parser.parseEnvelope(xml)).thenReturn(
                new SubmissionXmlParser.SubmissionEnvelope("demo", "1", instanceId, null));
        when(submissions.findByFormIdAndInstanceId(form.getId(), instanceId)).thenReturn(Optional.empty());
        when(datasets.loadActiveDatasetContents(version.getId())).thenReturn(Map.of());
        FormDef validatedForm = mock(FormDef.class);
        var json = new ObjectMapper().createObjectNode().put("name", "A");
        when(parser.parseAndValidate(version.getXmlContent(), xml, Map.of())).thenReturn(
                new SubmissionXmlParser.ValidatedSubmission(validatedForm, json));
        var processed = new SubmissionProcessingService.ProcessedSubmission(
                json, Map.of("name", "A"), Map.of(), instanceId);
        when(processing.processValidatedSubmission(json, xml, version, appUser, instanceId))
                .thenReturn(processed);
        when(submissions.saveAndFlush(any(Submission.class))).thenAnswer(inv -> {
            Submission s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
        when(persistence.persistProcessedData(
                any(Submission.class), eq(processed), eq(validatedForm), eq("KoboToolbox"),
                eq("RECEIVED"), eq("APP_USER"), eq(appUser.getId()), anyMap()))
                .thenAnswer(inv -> inv.getArgument(0));

        var receipt = service.receive(projectId, xmlFile, files, request);

        assertEquals(instanceId, receipt.instanceId());
        assertEquals("KoboToolbox", receipt.sourceClient());
        assertFalse(receipt.replacement());
        verify(persistence).persistProcessedData(
                any(Submission.class), eq(processed), eq(validatedForm), eq("KoboToolbox"),
                eq("RECEIVED"), eq("APP_USER"), eq(appUser.getId()), anyMap());
        verify(attachments).store(any(Submission.class), eq(photo), eq(xml));
        verify(audit).log(eq(AuditActions.SUBMISSION_RECEIVED), eq("SUBMISSION"), any(UUID.class), eq(instanceId), anyMap());
    }

    @Test
    void receive_duplicateStopsBeforeJavaRosaProcessingAndReturns409() {
        String instanceId = "uuid:duplicate";
        String xml = "<data id=\"demo\" version=\"1\"><meta><instanceID>"
                + instanceId + "</instanceID></meta></data>";
        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file", "submission.xml", "text/xml", xml.getBytes());
        when(parser.parseEnvelope(xml)).thenReturn(
                new SubmissionXmlParser.SubmissionEnvelope("demo", "1", instanceId, null));
        Submission existing = Submission.builder().id(UUID.randomUUID()).instanceId(instanceId).build();
        when(submissions.findByFormIdAndInstanceId(form.getId(), instanceId)).thenReturn(Optional.of(existing));

        OpenRosaSubmissionService.IntakeException error = assertThrows(
                OpenRosaSubmissionService.IntakeException.class,
                () -> service.receive(projectId, xmlFile, new LinkedMultiValueMap<>(), request));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(parser, never()).parseAndValidate(anyString(), anyString(), anyMap());
        verify(audit).log(eq(AuditActions.SUBMISSION_DUPLICATE), eq("SUBMISSION"), eq(existing.getId()), eq(instanceId), anyMap());
    }

    @Test
    void receive_javaRosaValidationFailureReturns400AndPersistsNothing() {
        String xml = "<data id=\"demo\" version=\"1\"><age>not-an-int</age></data>";
        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file", "submission.xml", "text/xml", xml.getBytes());
        when(parser.parseEnvelope(xml)).thenReturn(
                new SubmissionXmlParser.SubmissionEnvelope("demo", "1", null, null));
        when(submissions.findByFormIdAndInstanceId(eq(form.getId()), anyString())).thenReturn(Optional.empty());
        when(datasets.loadActiveDatasetContents(version.getId())).thenReturn(Map.of());
        when(parser.parseAndValidate(version.getXmlContent(), xml, Map.of()))
                .thenThrow(new IllegalArgumentException("invalid int"));

        OpenRosaSubmissionService.IntakeException error = assertThrows(
                OpenRosaSubmissionService.IntakeException.class,
                () -> service.receive(projectId, xmlFile, new LinkedMultiValueMap<>(), request));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(submissions, never()).saveAndFlush(any());
        verify(persistence, never()).persistProcessedData(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void explicitOlderVersionUsesMatchingV1WhenV2IsAlreadyLatest() throws Exception {
        FormVersion latest = FormVersion.builder()
                .id(UUID.randomUUID()).form(form).version("2").xmlns("demo")
                .xmlContent("<form-v2/>").build();
        String id = "uuid:version-one-after-upgrade";
        String xml = "<data id=\"demo\" version=\"1\"><name>old</name>"
                + "<meta><instanceID>" + id + "</instanceID></meta></data>";
        when(parser.parseEnvelope(xml)).thenReturn(
                new SubmissionXmlParser.SubmissionEnvelope("demo", "1", id, null));
        when(submissions.findByFormIdAndInstanceId(form.getId(), id))
                .thenReturn(Optional.empty());
        when(datasets.loadActiveDatasetContents(version.getId())).thenReturn(Map.of());
        FormDef parsedForm = mock(FormDef.class);
        var json = new ObjectMapper().createObjectNode().put("name", "old");
        when(parser.parseAndValidate(eq(version.getXmlContent()), eq(xml), anyMap()))
                .thenReturn(new SubmissionXmlParser.ValidatedSubmission(parsedForm, json));
        var processed = new SubmissionProcessingService.ProcessedSubmission(
                json, Map.of("name", "old"), Map.of(), id);
        when(processing.processValidatedSubmission(json, xml, version, appUser, id))
                .thenReturn(processed);
        when(submissions.saveAndFlush(any(Submission.class))).thenAnswer(inv -> {
            Submission s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
        when(persistence.persistProcessedData(any(Submission.class), eq(processed),
                eq(parsedForm), anyString(), eq("RECEIVED"), eq("APP_USER"),
                eq(appUser.getId()), anyMap())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.receive(projectId,
                new MockMultipartFile("xml_submission_file", "v1.xml",
                        "text/xml", xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new LinkedMultiValueMap<>(), request);

        assertEquals(id, result.instanceId());
        assertSame(version, result.submission().getFormVersion());
        verify(versions, never()).findLatestByFormId(form.getId());
        verify(parser).parseAndValidate(eq(version.getXmlContent()), eq(xml), anyMap());
        verify(parser, never()).parseAndValidate(eq(latest.getXmlContent()), anyString(), anyMap());
        verify(replacements, never()).createReplacement(any(), any(), anyString());
    }

    @Test
    void unknownExplicitVersionRejectsBeforeAnySubmissionOrAttachmentWrites() {
        String id = "uuid:unknown-version";
        String xml = "<data id=\"demo\" version=\"retired-v3\">"
                + "<meta><instanceID>" + id + "</instanceID></meta></data>";
        when(parser.parseEnvelope(xml)).thenReturn(
                new SubmissionXmlParser.SubmissionEnvelope("demo", "retired-v3", id, null));
        when(versions.findByFormIdAndVersion(form.getId(), "retired-v3"))
                .thenReturn(Optional.empty());

        var failure = assertThrows(OpenRosaSubmissionService.IntakeException.class,
                () -> service.receive(projectId,
                        new MockMultipartFile("xml_submission_file", "unknown.xml",
                                "text/xml", xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        new LinkedMultiValueMap<>(), request));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatus());
        verify(versions, never()).findLatestByFormId(any());
        verify(submissions, never()).findByFormIdAndInstanceId(any(), anyString());
        verify(submissions, never()).saveAndFlush(any());
        verify(replacements, never()).createReplacement(any(), any(), anyString());
        verify(parser, never()).parseAndValidate(anyString(), anyString(), anyMap());
        verify(processing, never()).processValidatedSubmission(
                any(), anyString(), any(), any(), anyString());
        verify(persistence, never()).persistProcessedData(
                any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(attachments, recordWork, audit);
    }

    @Test
    void missingVersionExplicitlyUsesLatestVersionRatherThanOlderDefinition()
            throws Exception {
        FormVersion latest = FormVersion.builder()
                .id(UUID.randomUUID()).form(form).version("2").xmlns("demo")
                .xmlContent("<form-v2/>").build();
        String id = "uuid:missing-version";
        String xml = "<data id=\"demo\"><name>new</name>"
                + "<meta><instanceID>" + id + "</instanceID></meta></data>";
        when(parser.parseEnvelope(xml)).thenReturn(
                new SubmissionXmlParser.SubmissionEnvelope("demo", null, id, null));
        when(versions.findLatestByFormId(form.getId())).thenReturn(Optional.of(latest));
        when(submissions.findByFormIdAndInstanceId(form.getId(), id))
                .thenReturn(Optional.empty());
        when(datasets.loadActiveDatasetContents(latest.getId())).thenReturn(Map.of());
        when(entityDatasets.loadReferencedEntityDatasets(latest))
                .thenReturn(new EntityListDatasetService.DatasetBundle(Map.of(), List.of()));
        FormDef parsedForm = mock(FormDef.class);
        var json = new ObjectMapper().createObjectNode().put("name", "new");
        when(parser.parseAndValidate(eq(latest.getXmlContent()), eq(xml), anyMap()))
                .thenReturn(new SubmissionXmlParser.ValidatedSubmission(parsedForm, json));
        var processed = new SubmissionProcessingService.ProcessedSubmission(
                json, Map.of("name", "new"), Map.of(), id);
        when(processing.processValidatedSubmission(json, xml, latest, appUser, id))
                .thenReturn(processed);
        when(submissions.saveAndFlush(any(Submission.class))).thenAnswer(inv -> {
            Submission s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
        when(persistence.persistProcessedData(any(Submission.class), eq(processed),
                eq(parsedForm), anyString(), eq("RECEIVED"), eq("APP_USER"),
                eq(appUser.getId()), anyMap())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.receive(projectId,
                new MockMultipartFile("xml_submission_file", "unversioned.xml",
                        "text/xml", xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new LinkedMultiValueMap<>(), request);

        assertSame(latest, result.submission().getFormVersion());
        verify(versions).findLatestByFormId(form.getId());
        verify(versions, never()).findByFormIdAndVersion(eq(form.getId()), anyString());
        verify(parser).parseAndValidate(eq(latest.getXmlContent()), eq(xml), anyMap());
        verify(parser, never()).parseAndValidate(eq(version.getXmlContent()), anyString(), anyMap());
    }

}
