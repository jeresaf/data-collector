package org.odk.platform.repository;

import org.odk.platform.domain.SubmissionVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for SubmissionVersion entity.
 */
@Repository
public interface SubmissionVersionRepository extends JpaRepository<SubmissionVersion, UUID> {

    List<SubmissionVersion> findBySubmissionIdOrderByVersionNumberAsc(UUID submissionId);

    Optional<SubmissionVersion> findBySubmissionIdAndVersionNumber(UUID submissionId, Integer versionNumber);

    Optional<SubmissionVersion> findByInstanceId(String instanceId);

    List<SubmissionVersion> findByDeprecatedId(String deprecatedId);

    @Query("SELECT sv FROM SubmissionVersion sv WHERE sv.submission.id = :submissionId ORDER BY sv.versionNumber DESC")
    List<SubmissionVersion> findBySubmissionIdOrderByVersionNumberDesc(@Param("submissionId") UUID submissionId);

    Optional<SubmissionVersion> findTopBySubmissionIdOrderByVersionNumberDesc(UUID submissionId);

    long countBySubmissionId(UUID submissionId);
}