package org.odk.platform.repository;

import org.odk.platform.domain.WebFormAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebFormAnswerRepository extends JpaRepository<WebFormAnswer, UUID> {

    List<WebFormAnswer> findBySessionId(UUID sessionId);

    List<WebFormAnswer> findBySessionIdAndQuestionPath(UUID sessionId, String questionPath);

    Optional<WebFormAnswer> findBySessionIdAndInstancePath(UUID sessionId, String instancePath);

    List<WebFormAnswer> findBySessionIdAndRepeatPath(UUID sessionId, String repeatPath);

    List<WebFormAnswer> findBySessionIdAndValidationStatus(UUID sessionId, String validationStatus);

    @Query("""
            SELECT a FROM WebFormAnswer a
            WHERE a.session.id = :sessionId
              AND a.questionPath = :questionPath
              AND ((:repeatPath IS NULL AND a.repeatPath IS NULL) OR a.repeatPath = :repeatPath)
              AND ((:repeatIndex IS NULL AND a.repeatIndex IS NULL) OR a.repeatIndex = :repeatIndex)
            """)
    Optional<WebFormAnswer> findBySessionAndQuestionAndRepeat(
            @Param("sessionId") UUID sessionId,
            @Param("questionPath") String questionPath,
            @Param("repeatPath") String repeatPath,
            @Param("repeatIndex") Integer repeatIndex);

    void deleteBySessionId(UUID sessionId);

    long countBySessionIdAndValidationStatus(UUID sessionId, String validationStatus);
}