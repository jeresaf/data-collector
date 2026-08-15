package org.odk.platform.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "web_form_answers", indexes = {
    @Index(name = "idx_web_form_answers_session_id", columnList = "session_id"),
    @Index(name = "idx_web_form_answers_question_path", columnList = "question_path"),
    @Index(name = "idx_web_form_answers_instance_path", columnList = "instance_path"),
    @Index(name = "idx_web_form_answers_repeat_path", columnList = "repeat_path"),
    @Index(name = "idx_web_form_answers_validation_status", columnList = "validation_status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebFormAnswer {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private WebFormSession session;

    @Column(name = "question_path", nullable = false, length = 500)
    private String questionPath;

    /**
     * Instance-specific JavaRosa path including multiplicities. This uniquely
     * identifies answers inside nested repeats where repeat_path + repeat_index
     * alone is ambiguous.
     */
    @Column(name = "instance_path", length = 1000)
    private String instancePath;

    @Column(name = "question_name", length = 255)
    private String questionName;

    @Column(name = "question_type", length = 100)
    private String questionType;

    @Column(name = "value_text", columnDefinition = "text")
    private String valueText;

    @Column(name = "value_number", precision = 19, scale = 6)
    private BigDecimal valueNumber;

    @Column(name = "value_date")
    private OffsetDateTime valueDate;

    @Column(name = "value_boolean")
    private Boolean valueBoolean;

    @Column(name = "value_json", columnDefinition = "jsonb")
    private String valueJson;

    @Column(name = "repeat_path", length = 500)
    private String repeatPath;

    @Column(name = "repeat_index")
    private Integer repeatIndex;

    @Column(name = "is_relevant", nullable = false)
    private Boolean isRelevant = true;

    @Column(name = "is_required", nullable = false)
    private Boolean isRequired = false;

    @Column(name = "constraint_message", columnDefinition = "text")
    private String constraintMessage;

    @Builder.Default
    @Column(name = "validation_status", nullable = false, length = 50)
    private String validationStatus = "pending";

    @Column(name = "answered_at", nullable = false)
    private OffsetDateTime answeredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}