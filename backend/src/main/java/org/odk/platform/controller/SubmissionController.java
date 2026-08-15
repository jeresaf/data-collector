package org.odk.platform.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.SubmissionAnswer;
import org.odk.platform.domain.SubmissionAttachment;
import org.odk.platform.domain.SubmissionEvent;
import org.odk.platform.service.SubmissionService;
import org.odk.platform.service.storage.StorageService;
import org.odk.platform.service.storage.StorageServiceFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/submissions")
@RequiredArgsConstructor
@Slf4j
public class SubmissionController {

    private final SubmissionService submissionService;
    private final StorageServiceFactory storageServiceFactory;
    private final AuditService auditService;

    /**
     * List submissions with filters and pagination.
     * Supports filtering by project, form, status, date range, source, and submitter.
     */
    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('DATA_ANALYST')")
    public ResponseEntity<Page<SubmissionListResponse>> listSubmissions(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID formId,
            @RequestParam(required = false) UUID formVersionId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) UUID submittedById,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @PageableDefault(size = 20, sort = "submittedAt") Pageable pageable) {

        log.debug("Listing submissions with filters: projectId={}, formId={}, formVersionId={}, status={}, source={}, submittedById={}, dateFrom={}, dateTo={}",
                projectId, formId, formVersionId, status, source, submittedById, dateFrom, dateTo);

        Page<Submission> submissions = submissionService.findSubmissions(
                projectId, formId, formVersionId, status, source, submittedById, dateFrom, dateTo, pageable);

        Page<SubmissionListResponse> response = submissions.map(this::toListResponse);
        return ResponseEntity.ok(response);
    }

    /**
     * Get submission count with same filters as list (for UI pagination info).
     */
    @GetMapping("/count")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('DATA_ANALYST')")
    public ResponseEntity<Long> countSubmissions(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID formId,
            @RequestParam(required = false) UUID formVersionId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) UUID submittedById,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {

        long count = submissionService.countSubmissions(
                projectId, formId, formVersionId, status, source, submittedById, dateFrom, dateTo);
        return ResponseEntity.ok(count);
    }

    /**
     * Get submission detail by ID.
     */
    @GetMapping("/{submissionId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('DATA_ANALYST')")
    public ResponseEntity<SubmissionDetailResponse> getSubmission(@PathVariable UUID submissionId) {
        return submissionService.findByIdWithDetails(submissionId)
                .map(this::toDetailResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Download raw submission XML.
     */
    @GetMapping({"/{submissionId}/xml", "/{submissionId}/download-xml"})
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('DATA_ANALYST')")
    public ResponseEntity<byte[]> downloadXml(@PathVariable UUID submissionId) {
        Optional<Submission> submissionOpt = submissionService.findById(submissionId);
        if (submissionOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Submission submission = submissionOpt.get();
        String xmlContent = submission.getXmlContent();
        if (xmlContent == null) {
            return ResponseEntity.notFound().build();
        }
        String fileName = "submission-" + submission.getInstanceId() + ".xml";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachmentDisposition(fileName))
                .body(xmlContent.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Download parsed submission JSON.
     */
    @GetMapping({"/{submissionId}/json", "/{submissionId}/download-json"})
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('DATA_ANALYST')")
    public ResponseEntity<byte[]> downloadJson(@PathVariable UUID submissionId) {
        Optional<Submission> submissionOpt = submissionService.findById(submissionId);
        if (submissionOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Submission submission = submissionOpt.get();
        String jsonContent = submission.getJsonContent();
        if (jsonContent == null) {
            return ResponseEntity.notFound().build();
        }
        String fileName = "submission-" + submission.getInstanceId() + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachmentDisposition(fileName))
                .body(jsonContent.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * List respondent attachments associated with the submission.
     */
    @GetMapping("/{submissionId}/attachments")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('DATA_ANALYST')")
    public ResponseEntity<List<AttachmentInfo>> listAttachments(
            @PathVariable UUID submissionId) {

        return submissionService.findByIdWithDetails(submissionId)
                .map(submission -> submission.getAttachmentsList() != null
                        ? submission.getAttachmentsList().stream()
                        .map(this::toAttachmentInfo)
                        .toList()
                        : List.<AttachmentInfo>of())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Download the original respondent attachment bytes from its configured
     * storage driver. This supports both OpenRosa attachments stored with an
     * absolute local path and web-form attachments stored with a driver-relative
     * object path.
     */
    @GetMapping("/{submissionId}/attachments/{attachmentId}/download")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('DATA_ANALYST')")
    public ResponseEntity<byte[]> downloadAttachment(
            @PathVariable UUID submissionId,
            @PathVariable UUID attachmentId) {

        Optional<SubmissionAttachment> attachmentOpt =
                submissionService.getAttachment(submissionId, attachmentId);

        if (attachmentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        SubmissionAttachment attachment = attachmentOpt.get();

        try {
            StorageService storageService =
                    storageServiceFactory.getStorageService(attachment.getStorageDriver());
            byte[] content = storageService.read(attachment.getStoragePath());

            return ResponseEntity.ok()
                    .contentType(safeMediaType(attachment.getContentType()))
                    .contentLength(content.length)
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            attachmentDisposition(attachment.getFileName()))
                    .body(content);
        } catch (IOException | RuntimeException e) {
            log.error(
                    "Failed to download submission attachment {} from {}: {}",
                    attachmentId,
                    attachment.getStoragePath(),
                    e.getMessage(),
                    e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Update submission status (review workflow).
     */
    @PutMapping("/{submissionId}/status")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<SubmissionDetailResponse> updateStatus(
            @PathVariable UUID submissionId,
            @RequestBody UpdateStatusRequest request) {
        Optional<Submission> before = submissionService.findById(submissionId);
        Optional<Submission> updated = submissionService.updateStatus(
                submissionId, request.getStatus(), request.getReviewNotes());
        if (updated.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Submission submission = updated.get();
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        before.map(Submission::getStatus).ifPresent(value -> details.put("oldStatus", value));
        if (submission.getStatus() != null) {
            details.put("newStatus", submission.getStatus());
        }
        if (request.getReviewNotes() != null) {
            details.put("reviewNotes", request.getReviewNotes());
        }
        if (submission.getForm() != null) {
            details.put("formId", submission.getForm().getId().toString());
        }
        auditService.log(
                AuditActions.SUBMISSION_STATUS_CHANGED,
                "SUBMISSION",
                submission.getId(),
                submission.getInstanceId(),
                details);

        return ResponseEntity.ok(toDetailResponse(submission));
    }

    /**
     * Get flattened answers for a submission (for reporting/export).
     */
    @GetMapping("/{submissionId}/answers")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('ADMIN') or hasRole('PROJECT_ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('DATA_ANALYST')")
    public ResponseEntity<Map<String, Object>> getFlattenedAnswers(@PathVariable UUID submissionId) {
        return submissionService.getFlattenedAnswersMap(submissionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private SubmissionListResponse toListResponse(Submission submission) {
        return SubmissionListResponse.builder()
                .id(submission.getId())
                .instanceId(submission.getInstanceId())
                .formId(submission.getForm().getId())
                .formTitle(submission.getForm().getTitle())
                .formXmlns(submission.getForm().getXmlns())
                .formVersionId(submission.getFormVersion() != null ? submission.getFormVersion().getId() : null)
                .formVersion(submission.getFormVersion() != null ? submission.getFormVersion().getVersion() : null)
                .status(submission.getStatus())
                .source(submissionSource(submission))
                .submittedAt(submission.getSubmittedAt())
                .submittedById(submission.getSubmittedBy() != null ? submission.getSubmittedBy().getId() : null)
                .submittedByName(submission.getSubmittedBy() != null ? submission.getSubmittedBy().getUsername() : null)
                .reviewedAt(submission.getReviewedAt())
                .reviewedById(submission.getReviewedBy() != null ? submission.getReviewedBy().getId() : null)
                .attachmentCount(submission.getAttachmentsList() != null ? submission.getAttachmentsList().size() : 0)
                .build();
    }

    private String submissionSource(Submission submission) {
        if (submission.getMetadata() == null) return null;
        Object sourceClient = submission.getMetadata().get("sourceClient");
        if (sourceClient != null) return String.valueOf(sourceClient);
        Object legacySource = submission.getMetadata().get("source");
        return legacySource == null ? null : String.valueOf(legacySource);
    }

    private SubmissionDetailResponse toDetailResponse(Submission submission) {
        return SubmissionDetailResponse.builder()
                .id(submission.getId())
                .instanceId(submission.getInstanceId())
                .formId(submission.getForm().getId())
                .formTitle(submission.getForm().getTitle())
                .formXmlns(submission.getForm().getXmlns())
                .formVersionId(submission.getFormVersion() != null ? submission.getFormVersion().getId() : null)
                .formVersion(submission.getFormVersion() != null ? submission.getFormVersion().getVersion() : null)
                .xmlContent(submission.getXmlContent())
                .jsonContent(submission.getJsonContent())
                .status(submission.getStatus())
                .metadata(submission.getMetadata())
                .submittedAt(submission.getSubmittedAt())
                .submittedById(submission.getSubmittedBy() != null ? submission.getSubmittedBy().getId() : null)
                .submittedByName(submission.getSubmittedBy() != null ? submission.getSubmittedBy().getUsername() : null)
                .reviewedAt(submission.getReviewedAt())
                .reviewedById(submission.getReviewedBy() != null ? submission.getReviewedBy().getId() : null)
                .reviewNotes(submission.getReviewNotes())
                .attachments(submission.getAttachmentsList() != null
                        ? submission.getAttachmentsList().stream()
                        .map(this::toAttachmentInfo)
                        .toList()
                        : List.of())
                .answers(submission.getAnswers() != null
                        ? submission.getAnswers().stream()
                        .map(a -> AnswerInfo.builder()
                                .id(a.getId())
                                .questionName(a.getFieldName())
                                .questionPath(a.getPath())
                                .value(getAnswerValue(a))
                                .repeatIndex(a.getRepeatIndex())
                                .build())
                        .toList()
                        : List.of())
                .events(submission.getEvents() != null
                        ? submission.getEvents().stream()
                        .map(e -> EventInfo.builder()
                                .id(e.getId())
                                .eventType(e.getEventType())
                                .eventData(e.getDetails() != null ? e.getDetails() : Map.of())
                                .createdAt(e.getCreatedAt().toInstant())
                                .createdById(e.getActorId())
                                .build())
                        .toList()
                        : List.of())
                .build();
    }

    private AttachmentInfo toAttachmentInfo(SubmissionAttachment attachment) {
        Map<String, Object> metadata =
                attachment.getMetadata() != null ? attachment.getMetadata() : Map.of();

        Object answerName = metadata.get("answerName");
        if (answerName == null && attachment.getAnswer() != null) {
            answerName = attachment.getAnswer().getFieldName();
        }

        return AttachmentInfo.builder()
                .id(attachment.getId())
                .fileName(attachment.getFileName())
                .contentType(attachment.getContentType())
                .fileSize(attachment.getFileSize())
                .answerName(answerName != null ? String.valueOf(answerName) : null)
                .build();
    }

    private MediaType safeMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private String attachmentDisposition(String fileName) {
        String safeName = fileName == null || fileName.isBlank()
                ? "download"
                : fileName;
        return ContentDisposition.attachment()
                .filename(safeName, StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    private String getAnswerValue(SubmissionAnswer answer) {
        if (answer.getValueText() != null) return answer.getValueText();
        if (answer.getValueNumber() != null) return answer.getValueNumber().toString();
        if (answer.getValueDate() != null) return answer.getValueDate().toString();
        if (answer.getValueBoolean() != null) return answer.getValueBoolean().toString();
        if (answer.getValueJson() != null) return answer.getValueJson();
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseEventData(String details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(details, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse event details: {}", details, e);
            return Map.of();
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SubmissionListResponse {
        private UUID id;
        private String instanceId;
        private UUID formId;
        private String formTitle;
        private String formXmlns;
        private UUID formVersionId;
        private String formVersion;
        private String status;
        private String source;
        private java.time.Instant submittedAt;
        private UUID submittedById;
        private String submittedByName;
        private java.time.Instant reviewedAt;
        private UUID reviewedById;
        private int attachmentCount;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SubmissionDetailResponse {
        private UUID id;
        private String instanceId;
        private UUID formId;
        private String formTitle;
        private String formXmlns;
        private UUID formVersionId;
        private String formVersion;
        private String xmlContent;
        private String jsonContent;
        private String status;
        private Map<String, Object> metadata;
        private java.time.Instant submittedAt;
        private UUID submittedById;
        private String submittedByName;
        private java.time.Instant reviewedAt;
        private UUID reviewedById;
        private String reviewNotes;
        private List<AttachmentInfo> attachments;
        private List<AnswerInfo> answers;
        private List<EventInfo> events;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AttachmentInfo {
        private UUID id;
        private String fileName;
        private String contentType;
        private Long fileSize;
        private String answerName;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AnswerInfo {
        private UUID id;
        private String questionName;
        private String questionPath;
        private String value;
        private Integer repeatIndex;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EventInfo {
        private UUID id;
        private String eventType;
        private Map<String, Object> eventData;
        private java.time.Instant createdAt;
        private UUID createdById;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class UpdateStatusRequest {
        private String status;
        private String reviewNotes;
    }
}