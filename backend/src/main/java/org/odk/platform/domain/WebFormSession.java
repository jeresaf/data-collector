package org.odk.platform.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "web_form_sessions", indexes = {
    @Index(name = "idx_web_form_sessions_form_version_id", columnList = "form_version_id"),
    @Index(name = "idx_web_form_sessions_user_id", columnList = "user_id"),
    @Index(name = "idx_web_form_sessions_app_user_id", columnList = "app_user_id"),
    @Index(name = "idx_web_form_sessions_status", columnList = "status"),
    @Index(name = "idx_web_form_sessions_started_at", columnList = "started_at"),
    @Index(name = "idx_web_form_sessions_last_activity_at", columnList = "last_activity_at"),
    @Index(name = "idx_web_form_sessions_submission_id", columnList = "submission_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebFormSession {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "form_version_id", nullable = false)
    private FormVersion formVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_user_id")
    private AppUser appUser;

    @Column(name = "status", nullable = false, length = 50)
    private String status = "draft";

    @Column(name = "current_screen", length = 255)
    private String currentScreen;

    @Column(name = "current_question_path", length = 500)
    private String currentQuestionPath;

    @Column(name = "session_data", columnDefinition = "jsonb")
    private String sessionData = "{}";

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "last_activity_at", nullable = false)
    private OffsetDateTime lastActivityAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id")
    private Submission submission;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;
}