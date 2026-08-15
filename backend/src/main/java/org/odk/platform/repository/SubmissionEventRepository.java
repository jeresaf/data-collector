package org.odk.platform.repository;

import org.odk.platform.domain.SubmissionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SubmissionEventRepository extends JpaRepository<SubmissionEvent, UUID> {

    List<SubmissionEvent> findBySubmissionIdOrderByCreatedAtAsc(UUID submissionId);

    @Query("SELECT se FROM SubmissionEvent se WHERE se.submission.id = :submissionId AND se.eventType = :eventType")
    List<SubmissionEvent> findBySubmissionIdAndEventType(@Param("submissionId") UUID submissionId, @Param("eventType") String eventType);

    void deleteBySubmissionId(UUID submissionId);
}