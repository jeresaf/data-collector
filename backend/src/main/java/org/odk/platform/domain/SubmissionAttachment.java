package org.odk.platform.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A respondent-supplied file uploaded together with an OpenRosa submission.
 *
 * This is intentionally separate from FormAttachment, which represents static
 * media/datasets distributed with a blank form.
 */
@Entity
@Table(name = "submission_attachments", indexes = {
        @Index(name = "idx_submission_attachments_submission_id", columnList = "submission_id"),
        @Index(name = "idx_submission_attachments_answer_id", columnList = "answer_id"),
        @Index(name = "idx_submission_attachments_content_hash", columnList = "content_hash")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubmissionAttachment {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    /**
     * The concrete normalized answer that references this respondent file.
     *
     * Nullable for defensive compatibility: OpenRosa clients may upload an
     * unreferenced extra file, and older rows predate answer-level linkage.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "answer_id")
    private SubmissionAnswer answer;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", length = 255)
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "storage_path", nullable = false, length = 500)
    private String storagePath;

    @Builder.Default
    @Column(name = "storage_driver", nullable = false, length = 50)
    private String storageDriver = "local";

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
