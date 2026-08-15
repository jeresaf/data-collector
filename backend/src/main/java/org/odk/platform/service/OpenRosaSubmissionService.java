package org.odk.platform.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.AppUserProjectRepository;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.SubmissionRepository;
import org.odk.platform.repository.XFormRepository;
import org.odk.platform.security.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Single transactional intake path for project-scoped and compatibility
 * OpenRosa submission endpoints.
 */
@Service
@RequiredArgsConstructor
public class OpenRosaSubmissionService {

    private final ProjectRepository projectRepository;
    private final XFormRepository xFormRepository;
    private final FormVersionRepository formVersionRepository;
    private final SubmissionRepository submissionRepository;
    private final AppUserProjectRepository appUserProjectRepository;
    private final CurrentUserService currentUserService;
    private final FormExternalDatasetService externalDatasetService;
    private final EntityListDatasetService entityListDatasetService;
    private final SubmissionXmlParser xmlParser;
    private final SubmissionProcessingService processingService;
    private final SubmissionPersistenceService persistenceService;
    private final SubmissionAttachmentService attachmentService;
    private final SubmissionReplacementService replacementService;
    private final AuditService auditService;

    @Transactional
    public Receipt receive(
            UUID projectId,
            MultipartFile submissionFile,
            MultiValueMap<String, MultipartFile> multipartFiles,
            HttpServletRequest request) {

        Project project = projectRepository.findById(projectId)
                .filter(Project::getIsActive)
                .orElseThrow(() -> new IntakeException(HttpStatus.NOT_FOUND, "Project not found"));

        if (submissionFile == null || submissionFile.isEmpty()) {
            throw new IntakeException(HttpStatus.BAD_REQUEST, "Missing xml_submission_file");
        }

        final String xmlContent;
        try {
            xmlContent = new String(submissionFile.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IntakeException(HttpStatus.BAD_REQUEST, "Could not read submission XML", e);
        }

        final SubmissionXmlParser.SubmissionEnvelope envelope;
        try {
            envelope = xmlParser.parseEnvelope(xmlContent);
        } catch (IllegalArgumentException e) {
            throw new IntakeException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }

        XForm form = xFormRepository.findByXmlnsAndProjectId(envelope.formId(), projectId)
                .filter(candidate -> Boolean.TRUE.equals(candidate.getIsPublished()))
                .filter(candidate -> Boolean.TRUE.equals(candidate.getIsActive()))
                .orElseThrow(() -> new IntakeException(
                        HttpStatus.NOT_FOUND, "Form not found or not published"));

        AppUser appUser = currentAppUser().orElse(null);
        if (appUser != null) {
            List<UUID> assignedFormIds = appUserProjectRepository
                    .findAssignedFormIdsByAppUserIdAndProjectId(appUser.getId(), projectId);
            if (!assignedFormIds.contains(form.getId())) {
                throw new IntakeException(
                        HttpStatus.FORBIDDEN, "App user is not assigned to this form");
            }
        }

        FormVersion formVersion = resolveFormVersion(form, envelope.version());
        if (formVersion.getXmlContent() == null || formVersion.getXmlContent().isBlank()) {
            throw new IntakeException(HttpStatus.NOT_FOUND, "Published form version has no XML");
        }

        String instanceId = envelope.instanceId();
        if (instanceId == null || instanceId.isBlank()) {
            instanceId = "uuid:" + UUID.randomUUID();
        }

        Optional<Submission> duplicate = submissionRepository
                .findByFormIdAndInstanceId(form.getId(), instanceId);
        if (duplicate.isPresent()) {
            Map<String, Object> details = auditDetails(
                    projectId, form, formVersion, instanceId, request);
            auditService.log(
                    AuditActions.SUBMISSION_DUPLICATE,
                    "SUBMISSION",
                    duplicate.get().getId(),
                    instanceId,
                    details);
            throw new IntakeException(HttpStatus.CONFLICT, "Duplicate submission instanceID");
        }

        Submission original = null;
        if (envelope.deprecatedId() != null) {
            original = submissionRepository
                    .findByFormIdAndInstanceId(form.getId(), envelope.deprecatedId())
                    .orElseThrow(() -> new IntakeException(
                            HttpStatus.NOT_FOUND, "Deprecated submission not found"));
        }

        final SubmissionXmlParser.ValidatedSubmission validated;
        try {
            Map<String, byte[]> datasets = new LinkedHashMap<>(
                    externalDatasetService.loadActiveDatasetContents(formVersion.getId()));
            datasets.putAll(
                    entityListDatasetService.loadReferencedEntityDatasets(formVersion).datasets());
            validated = xmlParser.parseAndValidate(
                    formVersion.getXmlContent(), xmlContent, datasets);
        } catch (RuntimeException e) {
            throw new IntakeException(
                    HttpStatus.BAD_REQUEST,
                    "Submission validation failed: " + safeMessage(e),
                    e);
        }

        SubmissionProcessingService.ProcessedSubmission processed =
                processingService.processValidatedSubmission(
                        validated.jsonContent(),
                        xmlContent,
                        formVersion,
                        appUser,
                        instanceId);

        String sourceClient = detectSourceClient(request);
        Map<String, Object> initialMetadata = requestMetadata(request, sourceClient);

        Submission submission = Submission.builder()
                .instanceId(instanceId)
                .xmlContent(xmlContent)
                .form(form)
                .formVersion(formVersion)
                .submittedBy(appUser)
                .submittedAt(Instant.now())
                .status("SUBMITTED")
                .metadata(initialMetadata)
                .build();

        boolean replacement = original != null;
        Submission saved = replacement
                ? replacementService.createReplacement(original, submission, envelope.deprecatedId())
                : submissionRepository.saveAndFlush(submission);

        Map<String, Object> eventDetails = auditDetails(
                projectId, form, formVersion, instanceId, request);
        if (replacement) {
            eventDetails.put("deprecatedId", envelope.deprecatedId());
            eventDetails.put("replacesSubmissionId", original.getId().toString());
        }

        saved = persistenceService.persistProcessedData(
                saved,
                processed,
                validated.formDef(),
                sourceClient,
                replacement ? "REPLACED" : "RECEIVED",
                appUser == null ? null : "APP_USER",
                appUser == null ? null : appUser.getId(),
                eventDetails);

        storeAttachments(saved, multipartFiles, xmlContent);

        auditService.log(
                replacement ? AuditActions.SUBMISSION_REPLACED : AuditActions.SUBMISSION_RECEIVED,
                "SUBMISSION",
                saved.getId(),
                instanceId,
                eventDetails);

        return new Receipt(saved, instanceId, replacement, sourceClient);
    }

    private FormVersion resolveFormVersion(XForm form, String submittedVersion) {
        if (submittedVersion != null && !submittedVersion.isBlank()) {
            Optional<FormVersion> exact = formVersionRepository
                    .findByFormIdAndVersion(form.getId(), submittedVersion);
            if (exact.isPresent()) return exact.get();
        }
        return formVersionRepository.findLatestByFormId(form.getId())
                .orElseThrow(() -> new IntakeException(
                        HttpStatus.NOT_FOUND, "Published form version not found"));
    }

    private Optional<AppUser> currentAppUser() {
        try {
            return currentUserService.getCurrentUser()
                    .filter(AppUser.class::isInstance)
                    .map(AppUser.class::cast);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private void storeAttachments(
            Submission submission,
            MultiValueMap<String, MultipartFile> multipartFiles,
            String xmlContent) {
        if (multipartFiles == null) return;
        for (Map.Entry<String, List<MultipartFile>> entry : multipartFiles.entrySet()) {
            if ("xml_submission_file".equals(entry.getKey())) continue;
            if (entry.getValue() == null) continue;
            for (MultipartFile file : entry.getValue()) {
                if (file != null && !file.isEmpty()) {
                    try {
                        attachmentService.store(submission, file, xmlContent);
                    } catch (IOException e) {
                        throw new IntakeException(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "Failed to store submission attachment: " + safeMessage(e),
                                e);
                    }
                }
            }
        }
    }

    private Map<String, Object> requestMetadata(
            HttpServletRequest request,
            String sourceClient) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceClient", sourceClient);
        String userAgent = request.getHeader("User-Agent");
        String version = request.getHeader("X-OpenRosa-Version");
        if (userAgent != null && !userAgent.isBlank()) metadata.put("userAgent", userAgent);
        if (version != null && !version.isBlank()) metadata.put("openRosaVersion", version);
        return metadata;
    }

    private Map<String, Object> auditDetails(
            UUID projectId,
            XForm form,
            FormVersion formVersion,
            String instanceId,
            HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("projectId", projectId.toString());
        details.put("formId", form.getId().toString());
        details.put("xmlFormId", form.getXmlns());
        details.put("formVersionId", formVersion.getId().toString());
        details.put("formVersion", formVersion.getVersion());
        details.put("instanceId", instanceId);
        details.put("source", detectSourceClient(request));
        return details;
    }

    private String detectSourceClient(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        String openRosaVersion = request.getHeader("X-OpenRosa-Version");
        String normalized = userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);

        if (normalized.contains("odk collect")) return "ODK Collect";
        if (normalized.contains("kobocollect")
                || normalized.contains("kobotoolbox")
                || normalized.contains("kobo collect")) return "KoboToolbox";
        if (normalized.contains("enketo")) return "Enketo";
        if (openRosaVersion != null && !openRosaVersion.isBlank()) return "OpenRosa Client";
        return "Unknown";
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }

    public record Receipt(
            Submission submission,
            String instanceId,
            boolean replacement,
            String sourceClient) {
    }

    public static class IntakeException extends RuntimeException {
        private final HttpStatus status;

        public IntakeException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public IntakeException(HttpStatus status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public HttpStatus getStatus() {
            return status;
        }
    }
}
