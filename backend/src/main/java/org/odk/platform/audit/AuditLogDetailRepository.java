package org.odk.platform.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogDetailRepository extends JpaRepository<AuditLogDetail, UUID> {
    List<AuditLogDetail> findByAuditLogIdOrderByCreatedAtAsc(UUID auditLogId);
    void deleteByAuditLogId(UUID auditLogId);
}
