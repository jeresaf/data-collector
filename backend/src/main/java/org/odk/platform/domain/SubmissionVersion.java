package org.odk.platform.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Entity representing a version of a submission (for replacement/update tracking).
 */
@Entity
@Table(name = "submission_versions", indexes = {
    @Index(name = "idx_submission_versions_submission_id", columnList = "submission_id"),
    @Index(name = "idx_submission_versions_instance_id", columnList = "instance_id"),
    @Index(name = "idx_submission_versions_deprecated_id", columnList = "deprecated_id"),
    @Index(name = "idx_submission_versions_version_number", columnList = "submission_id, version_number"),
    @Index(name = "idx_submission_versions_submitted_at", columnList = "submitted_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubmissionVersion {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "xml_content", nullable = false, columnDefinition = "TEXT")
    private String xmlContent;

    @Column(name = "json_content", columnDefinition = "JSONB")
    private String jsonContent;

    @Column(name = "instance_id", nullable = false, length = 255)
    private String instanceId;

    @Column(name = "deprecated_id", length = 255)
    private String deprecatedId;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "SUBMITTED";

    @Column(name = "metadata", columnDefinition = "JSONB")
    private String metadata;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitted_by_id")
    private AppUser submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private OffsetDateTime submittedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}