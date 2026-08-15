package org.odk.platform.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "submission_answers", indexes = {
    @Index(name = "idx_submission_answers_submission_id", columnList = "submission_id"),
    @Index(name = "idx_submission_answers_path", columnList = "path"),
    @Index(name = "idx_submission_answers_field_name", columnList = "field_name"),
    @Index(name = "idx_submission_answers_repeat_path", columnList = "repeat_path")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubmissionAnswer {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @Column(name = "path", nullable = false, length = 500)
    private String path;

    @Column(name = "field_name", nullable = false, length = 255)
    private String fieldName;

    @Column(name = "field_type", length = 100)
    private String fieldType;

    @Column(name = "value_text", columnDefinition = "TEXT")
    private String valueText;

    @Column(name = "value_number", precision = 19, scale = 6)
    private java.math.BigDecimal valueNumber;

    @Column(name = "value_date")
    private OffsetDateTime valueDate;

    @Column(name = "value_boolean")
    private Boolean valueBoolean;

    @Column(name = "value_json", columnDefinition = "JSONB")
    private String valueJson;

    @Column(name = "repeat_path", length = 500)
    private String repeatPath;

    @Column(name = "repeat_index")
    private Integer repeatIndex;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}