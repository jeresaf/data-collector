package org.odk.platform.repository;

import org.odk.platform.domain.SubmissionAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SubmissionAnswerRepository extends JpaRepository<SubmissionAnswer, UUID> {

    List<SubmissionAnswer> findBySubmissionId(UUID submissionId);

    List<SubmissionAnswer> findBySubmissionIdAndPath(UUID submissionId, String path);

    /**
     * Resolve respondent-file answers by their exact stored filename. The
     * submission scope prevents a filename reused by another submission from
     * being linked accidentally.
     */
    List<SubmissionAnswer> findBySubmissionIdAndValueText(UUID submissionId, String valueText);

    @Query("SELECT sa FROM SubmissionAnswer sa WHERE sa.submission.id = :submissionId AND sa.repeatPath = :repeatPath")
    List<SubmissionAnswer> findBySubmissionIdAndRepeatPath(@Param("submissionId") UUID submissionId, @Param("repeatPath") String repeatPath);

    @Query("SELECT sa FROM SubmissionAnswer sa WHERE sa.submission.id = :submissionId AND sa.repeatPath = :repeatPath AND sa.repeatIndex = :repeatIndex")
    List<SubmissionAnswer> findBySubmissionIdAndRepeatPathAndRepeatIndex(
            @Param("submissionId") UUID submissionId,
            @Param("repeatPath") String repeatPath,
            @Param("repeatIndex") Integer repeatIndex);

    void deleteBySubmissionId(UUID submissionId);
}