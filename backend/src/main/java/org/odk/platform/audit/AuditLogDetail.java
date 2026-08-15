package org.odk.platform.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Optional structured detail attached to one audit event.
 *
 * The main audit_logs row is intentionally kept as the event envelope. This table
 * is used when an event benefits from field-level old/new values without forcing
 * every audit event into a domain-specific schema.
 */
@Entity
@Table(name = "audit_log_details", indexes = {
        @Index(name = "idx_audit_log_details_audit_log_id", columnList = "audit_log_id"),
        @Index(name = "idx_audit_log_details_detail_key", columnList = "detail_key"),
        @Index(name = "idx_audit_log_details_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLogDetail {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "audit_log_id", nullable = false)
    private UUID auditLogId;

    @Column(name = "detail_key", nullable = false, length = 255)
    private String detailKey;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(name = "value_type", length = 50)
    private String valueType;

    @Builder.Default
    @Column(name = "metadata", nullable = false, columnDefinition = "TEXT")
    private String metadata = "{}";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
