package org.odk.platform.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "form_publish_history", indexes = {
    @Index(name = "idx_form_publish_history_form_version", columnList = "form_version_id"),
    @Index(name = "idx_form_publish_history_performed_by", columnList = "performed_by"),
    @Index(name = "idx_form_publish_history_performed_at", columnList = "performed_at"),
    @Index(name = "idx_form_publish_history_action", columnList = "action")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormPublishHistory {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "form_version_id", nullable = false)
    private FormVersion formVersion;

    @Column(name = "action", nullable = false, length = 50)
    private String action; // PUBLISHED, DEPRECATED, ARCHIVED, REPUBLISHED

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_version_id")
    private FormVersion previousVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by", nullable = false)
    private User performedBy;

    @CreationTimestamp
    @Column(name = "performed_at", nullable = false, updatable = false)
    private Instant performedAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}