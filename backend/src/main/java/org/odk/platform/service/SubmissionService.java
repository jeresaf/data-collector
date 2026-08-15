package org.odk.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javarosa.core.model.FormDef;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.SubmissionAttachment;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.SubmissionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing form submissions - storage, retrieval, and processing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final SubmissionProcessingService processingService;
    private final JavaRosaFormLoader formLoader;
    private final FormVersionService formVersionService;

    /**
     * Process and save a new submission.
     */
    @Transactional
    public Submission createSubmission(String xmlContent, XForm form, UUID formVersionId, UUID submittedById) {
        log.info("Creating submission for form: {}", form.getId());

        // Load form definition for validation
        FormDef formDef = formLoader.loadFormDef(xmlContent);
        if (formDef == null) {
            throw new IllegalStateException("Failed to load form definition for form: " + form.getId());
        }

        // Process the submission
        SubmissionProcessingService.ProcessedSubmission processed = 
            processingService.processSubmission(xmlContent, formDef);

        // Create submission entity
        Submission submission = new Submission();
        submission.setInstanceId(processed.getInstanceId());
        submission.setXmlContent(xmlContent);
        submission.setJsonContent(processed.getJsonContent().toString());
        submission.setForm(form);
        submission.setSubmittedAt(Instant.now());
        submission.setStatus("SUBMITTED");
        
        // Set metadata
        submission.setMetadata(processed.getMetadata());
        
        // Set form version if provided
        if (formVersionId != null) {
            formVersionService.findById(formVersionId).ifPresent(submission::setFormVersion);
        }

        // Set submitted by if provided
        if (submittedById != null) {
            // This would be set via the AppUser entity
            // submission.setSubmittedBy(appUserRepository.getReferenceById(submittedById));
        }

        return submissionRepository.save(submission);
    }

    /**
     * Find submission by instance ID.
     */
    public Optional<Submission> findByInstanceId(String instanceId) {
        return submissionRepository.findByInstanceId(instanceId);
    }

    /**
     * Find submission by ID.
     */
    public Optional<Submission> findById(UUID id) {
        return submissionRepository.findById(id);
    }

    /**
     * Get all submissions for a form.
     */
    public List<Submission> findByFormId(UUID formId) {
        return submissionRepository.findByFormId(formId);
    }

    /**
     * Get submissions for a form with pagination.
     */
    public List<Submission> findByFormId(UUID formId, int offset, int limit) {
        return submissionRepository.findByFormId(formId, offset, limit);
    }

    /**
     * Get submissions by form version.
     */
    public List<Submission> findByFormVersionId(UUID formVersionId) {
        return submissionRepository.findByFormVersionId(formVersionId);
    }

    /**
     * Get submissions by status.
     */
    public List<Submission> findByStatus(String status) {
        return submissionRepository.findByStatus(status);
    }

    /**
     * Update submission status (e.g., for review workflow).
     */
    @Transactional
    public Submission updateStatus(UUID submissionId, String status, UUID reviewedById, String reviewNotes) {
        Submission submission = submissionRepository.findById(submissionId)
            .orElseThrow(() -> new IllegalArgumentException("Submission not found: " + submissionId));
        
        submission.setStatus(status);
        submission.setReviewedAt(Instant.now());
        submission.setReviewNotes(reviewNotes);
        // submission.setReviewedBy(userRepository.getReferenceById(reviewedById));
        
        return submissionRepository.save(submission);
    }

    /**
     * Get submission count for a form.
     */
    public long countByFormId(UUID formId) {
        return submissionRepository.countByFormId(formId);
    }

    /**
     * Get submission count by status for a form.
     */
    public long countByFormIdAndStatus(UUID formId, String status) {
        return submissionRepository.countByFormIdAndStatus(formId, status);
    }

    /**
     * Delete a submission (soft delete by marking as DELETED).
     */
    @Transactional
    public void deleteSubmission(UUID submissionId) {
        Submission submission = submissionRepository.findById(submissionId)
            .orElseThrow(() -> new IllegalArgumentException("Submission not found: " + submissionId));
        
        submission.setStatus("DELETED");
        submissionRepository.save(submission);
    }

    /**
     * Get flattened answers for a submission.
     */
    public JsonNode getFlattenedAnswers(UUID submissionId) {
        Submission submission = submissionRepository.findById(submissionId)
            .orElseThrow(() -> new IllegalArgumentException("Submission not found: " + submissionId));
        
        // Parse the JSON content and flatten
        try {
            JsonNode jsonContent = new com.fasterxml.jackson.databind.ObjectMapper().readTree(submission.getJsonContent());
            Map<String, Object> flattened = processingService.flattenAnswers(jsonContent);
            return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(flattened);
        } catch (Exception e) {
            log.error("Failed to flatten answers for submission: {}", submissionId, e);
            return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        }
    }

    /**
     * Find submissions with filters and pagination.
     */
    public Page<Submission> findSubmissions(
            UUID projectId, UUID formId, UUID formVersionId, String status,
            String source, UUID submittedById, String dateFrom, String dateTo,
            Pageable pageable) {
        
        Instant from = dateFrom != null ? Instant.parse(dateFrom) : null;
        Instant to = dateTo != null ? Instant.parse(dateTo) : null;
        
        return submissionRepository.findSubmissions(
                projectId, formId, formVersionId, status, source, submittedById, from, to, pageable);
    }

    /**
     * Count submissions with filters.
     */
    public long countSubmissions(
            UUID projectId, UUID formId, UUID formVersionId, String status,
            String source, UUID submittedById, String dateFrom, String dateTo) {
        
        Instant from = dateFrom != null ? Instant.parse(dateFrom) : null;
        Instant to = dateTo != null ? Instant.parse(dateTo) : null;
        
        return submissionRepository.countSubmissions(
                projectId, formId, formVersionId, status, source, submittedById, from, to);
    }

    /**
     * Find submission by ID with all details fetched.
     */
    public Optional<Submission> findByIdWithDetails(UUID submissionId) {
        return submissionRepository.findByIdWithDetails(submissionId);
    }

    /**
     * Get attachment by submission ID and attachment ID.
     */
    public Optional<SubmissionAttachment> getAttachment(UUID submissionId, UUID attachmentId) {
        return submissionRepository.findAttachmentBySubmissionIdAndId(submissionId, attachmentId);
    }

    /**
     * Update submission status with review notes.
     */
    @Transactional
    public Optional<Submission> updateStatus(UUID submissionId, String status, String reviewNotes) {
        return submissionRepository.findById(submissionId)
                .map(submission -> {
                    submission.setStatus(status);
                    submission.setReviewedAt(Instant.now());
                    submission.setReviewNotes(reviewNotes);
                    // submission.setReviewedBy(currentUserService.getCurrentUser());
                    return submissionRepository.save(submission);
                });
    }

    /**
     * Get flattened answers as a Map.
     */
    public Optional<Map<String, Object>> getFlattenedAnswersMap(UUID submissionId) {
        return submissionRepository.findById(submissionId)
                .map(submission -> {
                    try {
                        JsonNode jsonContent = new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(submission.getJsonContent());
                        return processingService.flattenAnswers(jsonContent);
                    } catch (Exception e) {
                        log.error("Failed to flatten answers for submission: {}", submissionId, e);
                        return Map.of();
                    }
                });
    }
}
