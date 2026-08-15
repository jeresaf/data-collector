package org.odk.platform.repository;

import org.odk.platform.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<AuditLog> findByAppUserIdOrderByCreatedAtDesc(UUID appUserId);

    List<AuditLog> findByActionOrderByCreatedAtDesc(String action);

    List<AuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(Instant start, Instant end);

    @Query("SELECT a FROM AuditLog a WHERE a.user.id = :actorId AND a.createdAt BETWEEN :start AND :end ORDER BY a.createdAt DESC")
    List<AuditLog> findByUserIdAndDateRange(@Param("actorId") UUID actorId, @Param("start") Instant start, @Param("end") Instant end);

    @Query("SELECT a FROM AuditLog a WHERE a.appUser.id = :actorId AND a.createdAt BETWEEN :start AND :end ORDER BY a.createdAt DESC")
    List<AuditLog> findByAppUserIdAndDateRange(@Param("actorId") UUID actorId, @Param("start") Instant start, @Param("end") Instant end);

    long countByActionAndCreatedAtBetween(String action, Instant start, Instant end);
}
