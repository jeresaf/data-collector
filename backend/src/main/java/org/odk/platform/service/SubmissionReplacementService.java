package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.Submission;
import org.odk.platform.repository.SubmissionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Handles OpenRosa deprecatedID replacement semantics.
 *
 * The original submission remains in submissions. Before the replacement is
 * inserted, its current state is copied to submission_versions so history is
 * retained for audit/recovery.
 */
@Service
@RequiredArgsConstructor
public class SubmissionReplacementService {

    private final JdbcTemplate jdbcTemplate;
    private final SubmissionRepository submissionRepository;

    @Transactional
    public Submission createReplacement(
            Submission original,
            Submission replacement,
            String deprecatedId) {

        if (original == null || original.getId() == null) {
            throw new IllegalArgumentException("Original submission is required");
        }
        if (replacement == null) {
            throw new IllegalArgumentException("Replacement submission is required");
        }

        Integer nextVersion = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(MAX(version_number), 0) + 1
                FROM submission_versions
                WHERE submission_id = ?
                """,
                Integer.class,
                original.getId());

        if (nextVersion == null) {
            nextVersion = 1;
        }

        AppUser submittedBy = original.getSubmittedBy();
        UUID submittedById = submittedBy != null ? submittedBy.getId() : null;

        Instant submittedAt = original.getSubmittedAt() != null
                ? original.getSubmittedAt()
                : Instant.now();

        jdbcTemplate.update(
                """
                INSERT INTO submission_versions (
                    id,
                    submission_id,
                    version_number,
                    xml_content,
                    instance_id,
                    deprecated_id,
                    status,
                    submitted_by_id,
                    submitted_at,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                original.getId(),
                nextVersion,
                original.getXmlContent(),
                original.getInstanceId(),
                deprecatedId,
                original.getStatus() != null ? original.getStatus() : "SUBMITTED",
                submittedById,
                Timestamp.from(submittedAt));

        return submissionRepository.saveAndFlush(replacement);
    }
}
