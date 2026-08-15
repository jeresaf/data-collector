package org.odk.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.SubmissionAttachment;
import org.odk.platform.domain.XForm;
import org.odk.platform.service.SubmissionService;
import org.odk.platform.service.storage.StorageService;
import org.odk.platform.service.storage.StorageServiceFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SubmissionController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class SubmissionReviewIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean SubmissionService submissionService;
    @MockBean StorageServiceFactory storageServiceFactory;
    @MockBean AuditService auditService;

    // Keep unrelated global infrastructure out of this MVC slice.
    @MockBean org.odk.platform.security.OpenRosaBasicAuthFilter openRosaBasicAuthFilter;
    @MockBean org.odk.platform.security.JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean(name = "jpaMappingContext") JpaMetamodelMappingContext jpaMappingContext;

    private StorageService storageService;

    private UUID submissionId;
    private UUID attachmentId;
    private Submission submission;
    private SubmissionAttachment attachment;

    @BeforeEach
    void setUp() {
        storageService = org.mockito.Mockito.mock(StorageService.class);

        submissionId = UUID.randomUUID();
        attachmentId = UUID.randomUUID();

        XForm form = XForm.builder()
                .id(UUID.randomUUID())
                .project(Project.builder().id(UUID.randomUUID()).name("Review Project").build())
                .title("Household Review")
                .xmlns("household_review")
                .build();

        submission = Submission.builder()
                .id(submissionId)
                .form(form)
                .instanceId("uuid:review-001")
                .xmlContent("<data id=\"household_review\"><name>Amina</name></data>")
                .jsonContent("{\"name\":\"Amina\"}")
                .status("SUBMITTED")
                .metadata(Map.of("sourceClient", "WEB"))
                .submittedAt(Instant.parse("2026-08-15T06:00:00Z"))
                .attachmentsList(new LinkedHashSet<>())
                .answers(new LinkedHashSet<>())
                .events(new LinkedHashSet<>())
                .build();

        attachment = SubmissionAttachment.builder()
                .id(attachmentId)
                .submission(submission)
                .fileName("photo.jpg")
                .contentType("image/jpeg")
                .fileSize(4L)
                .storageDriver("local")
                .storagePath("/var/lib/odk/submissions/review/photo.jpg")
                .metadata(Map.of("answerName", "photo"))
                .build();

        submission.getAttachmentsList().add(attachment);
    }

    @Test
    @WithMockUser(username = "admin", roles = {"SYSTEM_ADMIN"})
    void detailAndAttachmentListExposeReviewData() throws Exception {
        when(submissionService.findByIdWithDetails(submissionId))
                .thenReturn(Optional.of(submission));

        mockMvc.perform(get("/api/submissions/{submissionId}", submissionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(submissionId.toString()))
                .andExpect(jsonPath("$.instanceId").value("uuid:review-001"))
                .andExpect(jsonPath("$.formTitle").value("Household Review"))
                .andExpect(jsonPath("$.metadata.sourceClient").value("WEB"))
                .andExpect(jsonPath("$.attachments[0].id").value(attachmentId.toString()))
                .andExpect(jsonPath("$.attachments[0].answerName").value("photo"));

        mockMvc.perform(get(
                        "/api/submissions/{submissionId}/attachments",
                        submissionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(attachmentId.toString()))
                .andExpect(jsonPath("$[0].fileName").value("photo.jpg"))
                .andExpect(jsonPath("$[0].contentType").value("image/jpeg"));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"SYSTEM_ADMIN"})
    void xmlAndJsonDownloadAliasesReturnOriginalPayloads() throws Exception {
        when(submissionService.findById(submissionId))
                .thenReturn(Optional.of(submission));

        mockMvc.perform(get(
                        "/api/submissions/{submissionId}/download-xml",
                        submissionId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(submission.getXmlContent()))
                .andExpect(header().string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString(".xml")));

        mockMvc.perform(get(
                        "/api/submissions/{submissionId}/download-json",
                        submissionId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(submission.getJsonContent()))
                .andExpect(header().string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString(".json")));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"SYSTEM_ADMIN"})
    void attachmentDownloadReturnsStoredBytesAndOriginalMediaType() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3, 4};

        when(submissionService.getAttachment(submissionId, attachmentId))
                .thenReturn(Optional.of(attachment));
        when(storageServiceFactory.getStorageService("local"))
                .thenReturn(storageService);
        when(storageService.read(attachment.getStoragePath()))
                .thenReturn(bytes);

        mockMvc.perform(get(
                        "/api/submissions/{submissionId}/attachments/{attachmentId}/download",
                        submissionId,
                        attachmentId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(bytes))
                .andExpect(header().longValue("Content-Length", bytes.length))
                .andExpect(header().string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString("photo.jpg")));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"SYSTEM_ADMIN"})
    void statusReviewReturnsUpdatedDetailAndEmitsAudit() throws Exception {
        Submission reviewed = Submission.builder()
                .id(submissionId)
                .form(submission.getForm())
                .instanceId(submission.getInstanceId())
                .xmlContent(submission.getXmlContent())
                .jsonContent(submission.getJsonContent())
                .status("APPROVED")
                .metadata(submission.getMetadata())
                .submittedAt(submission.getSubmittedAt())
                .reviewedAt(Instant.parse("2026-08-15T06:30:00Z"))
                .reviewNotes("Checked and approved")
                .attachmentsList(new LinkedHashSet<>())
                .answers(new LinkedHashSet<>())
                .events(new LinkedHashSet<>())
                .build();

        when(submissionService.findById(submissionId))
                .thenReturn(Optional.of(submission));
        when(submissionService.updateStatus(
                submissionId,
                "APPROVED",
                "Checked and approved"))
                .thenReturn(Optional.of(reviewed));

        String request = objectMapper.writeValueAsString(Map.of(
                "status", "APPROVED",
                "reviewNotes", "Checked and approved"));

        mockMvc.perform(put(
                        "/api/submissions/{submissionId}/status",
                        submissionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewNotes").value("Checked and approved"));

        verify(auditService).log(
                eq(org.odk.platform.audit.AuditActions.SUBMISSION_STATUS_CHANGED),
                eq("SUBMISSION"),
                eq(submissionId),
                eq("uuid:review-001"),
                anyMap());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"SYSTEM_ADMIN"})
    void missingReviewResourcesReturnNotFound() throws Exception {
        UUID missing = UUID.randomUUID();

        when(submissionService.findByIdWithDetails(missing))
                .thenReturn(Optional.empty());
        when(submissionService.findById(missing))
                .thenReturn(Optional.empty());
        when(submissionService.getAttachment(missing, attachmentId))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/submissions/{submissionId}", missing))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(
                        "/api/submissions/{submissionId}/attachments",
                        missing))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(
                        "/api/submissions/{submissionId}/download-xml",
                        missing))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(
                        "/api/submissions/{submissionId}/attachments/{attachmentId}/download",
                        missing,
                        attachmentId))
                .andExpect(status().isNotFound());
    }
}
