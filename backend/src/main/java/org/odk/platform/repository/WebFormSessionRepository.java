package org.odk.platform.repository;

import org.odk.platform.domain.WebFormSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebFormSessionRepository extends JpaRepository<WebFormSession, UUID> {

    List<WebFormSession> findByFormVersionId(UUID formVersionId);

    List<WebFormSession> findByUserId(UUID userId);

    List<WebFormSession> findByAppUserId(UUID appUserId);

    List<WebFormSession> findByStatus(String status);

    List<WebFormSession> findByFormVersionIdAndStatus(UUID formVersionId, String status);

    Optional<WebFormSession> findBySubmissionId(UUID submissionId);

    @Query("SELECT s FROM WebFormSession s WHERE s.formVersion.id = :formVersionId AND s.user.id = :userId AND s.status = 'draft' ORDER BY s.lastActivityAt DESC")
    Optional<WebFormSession> findActiveDraftByFormVersionAndUser(@Param("formVersionId") UUID formVersionId, @Param("userId") UUID userId);

    @Query("SELECT s FROM WebFormSession s WHERE s.formVersion.id = :formVersionId AND s.appUser.id = :appUserId AND s.status = 'draft' ORDER BY s.lastActivityAt DESC")
    Optional<WebFormSession> findActiveDraftByFormVersionAndAppUser(@Param("formVersionId") UUID formVersionId, @Param("appUserId") UUID appUserId);

    List<WebFormSession> findByLastActivityAtBefore(OffsetDateTime cutoff);

    long countByFormVersionIdAndStatus(UUID formVersionId, String status);
}