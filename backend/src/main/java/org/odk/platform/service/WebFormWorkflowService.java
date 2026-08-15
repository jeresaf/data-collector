package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.odk.platform.domain.User;
import org.odk.platform.security.CurrentUserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Read-only workflow projection for the user-facing web-form application shell.
 *
 * JavaRosa runtime/navigation/finalization remains owned by WebFormController and
 * WebFormRuntimeService. This service only exposes the authenticated user's
 * accessible forms, drafts/submitted sessions and review-ready persisted answers.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WebFormWorkflowService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final WebFormAccessService webFormAccessService;

    public List<AvailableFormView> listAvailableForms() {
        requireCurrentWebUser();

        List<AvailableFormView> candidates = jdbcTemplate.query("""
                SELECT
                    f.id AS form_id,
                    f.title AS form_title,
                    f.description AS form_description,
                    p.id AS project_id,
                    p.name AS project_name,
                    fv.id AS form_version_id,
                    fv.version AS form_version,
                    fv.published_at
                FROM xforms f
                JOIN projects p ON p.id = f.project_id
                JOIN form_versions fv ON fv.form_id = f.id
                WHERE f.is_active = TRUE
                  AND f.is_published = TRUE
                  AND p.is_active = TRUE
                  AND fv.is_published = TRUE
                  AND fv.is_latest = TRUE
                  AND fv.status = 'PUBLISHED'
                ORDER BY p.name, f.title, fv.version
                """, (rs, rowNum) -> new AvailableFormView(
                uuid(rs, "form_id"),
                rs.getString("form_title"),
                rs.getString("form_description"),
                uuid(rs, "project_id"),
                rs.getString("project_name"),
                uuid(rs, "form_version_id"),
                rs.getString("form_version"),
                instant(rs, "published_at")
        ));

        // Reuse the exact same authorization logic used by the JavaRosa runtime.
        List<AvailableFormView> allowed = new ArrayList<>();
        for (AvailableFormView form : candidates) {
            try {
                webFormAccessService.authorizeDefinition(form.formVersionId());
                allowed.add(form);
            } catch (AccessDeniedException ignored) {
                // Not visible in the user-facing catalog.
            }
        }
        return List.copyOf(allowed);
    }

    public AvailableFormView getAvailableForm(UUID formId) {
        return listAvailableForms().stream()
                .filter(form -> form.formId().equals(formId))
                .findFirst()
                .orElseThrow(() -> new AccessDeniedException(
                        "Form is not available to the current user"));
    }

    public List<SessionSummaryView> listSessions(String status) {
        User currentUser = requireCurrentWebUser();
        String normalizedStatus = normalizeStatus(status);

        List<SessionSummaryView> rows = jdbcTemplate.query("""
                SELECT
                    s.id AS session_id,
                    s.status,
                    s.started_at,
                    s.last_activity_at,
                    s.completed_at,
                    s.submitted_at,
                    s.submission_id,
                    fv.id AS form_version_id,
                    fv.version AS form_version,
                    f.id AS form_id,
                    f.title AS form_title,
                    p.id AS project_id,
                    p.name AS project_name,
                    sub.instance_id AS submission_instance_id
                FROM web_form_sessions s
                JOIN form_versions fv ON fv.id = s.form_version_id
                JOIN xforms f ON f.id = fv.form_id
                JOIN projects p ON p.id = f.project_id
                LEFT JOIN submissions sub ON sub.id = s.submission_id
                WHERE s.user_id = ?
                  AND s.status = ?
                ORDER BY s.last_activity_at DESC, s.started_at DESC
                """, (rs, rowNum) -> sessionSummary(rs),
                currentUser.getId(), normalizedStatus);

        List<SessionSummaryView> allowed = new ArrayList<>();
        for (SessionSummaryView row : rows) {
            try {
                webFormAccessService.authorizeSession(row.sessionId());
                allowed.add(row);
            } catch (AccessDeniedException ignored) {
                // Effective access was revoked; do not expose the session.
            }
        }
        return List.copyOf(allowed);
    }

    public SessionReviewView getReview(UUID sessionId) {
        webFormAccessService.authorizeSession(sessionId);

        SessionSummaryView session = jdbcTemplate.query("""
                SELECT
                    s.id AS session_id,
                    s.status,
                    s.started_at,
                    s.last_activity_at,
                    s.completed_at,
                    s.submitted_at,
                    s.submission_id,
                    fv.id AS form_version_id,
                    fv.version AS form_version,
                    f.id AS form_id,
                    f.title AS form_title,
                    p.id AS project_id,
                    p.name AS project_name,
                    sub.instance_id AS submission_instance_id
                FROM web_form_sessions s
                JOIN form_versions fv ON fv.id = s.form_version_id
                JOIN xforms f ON f.id = fv.form_id
                JOIN projects p ON p.id = f.project_id
                LEFT JOIN submissions sub ON sub.id = s.submission_id
                WHERE s.id = ?
                """, rs -> {
                    if (!rs.next()) {
                        throw new IllegalArgumentException(
                                "Web form session not found: " + sessionId);
                    }
                    return sessionSummary(rs);
                }, sessionId);

        List<ReviewAnswerView> answers = jdbcTemplate.query("""
                SELECT
                    a.id,
                    a.question_path,
                    a.instance_path,
                    a.question_name,
                    a.question_type,
                    a.value_text,
                    a.value_number,
                    a.value_date,
                    a.value_boolean,
                    a.value_json,
                    a.repeat_path,
                    a.repeat_index,
                    a.is_required,
                    a.validation_status,
                    a.constraint_message,
                    a.answered_at
                FROM web_form_answers a
                WHERE a.session_id = ?
                  AND a.is_relevant = TRUE
                ORDER BY a.created_at, a.id
                """, (rs, rowNum) -> new ReviewAnswerView(
                uuid(rs, "id"),
                rs.getString("question_path"),
                rs.getString("instance_path"),
                rs.getString("question_name"),
                rs.getString("question_type"),
                answerValue(rs),
                rs.getString("repeat_path"),
                nullableInteger(rs, "repeat_index"),
                rs.getBoolean("is_required"),
                rs.getString("validation_status"),
                rs.getString("constraint_message"),
                instant(rs, "answered_at")
        ), sessionId);

        List<ReviewAttachmentView> attachments = jdbcTemplate.query("""
                SELECT
                    a.id,
                    a.answer_id,
                    a.question_path,
                    a.file_name,
                    a.content_type,
                    a.file_size,
                    a.content_hash,
                    a.created_at
                FROM web_form_attachments a
                WHERE a.session_id = ?
                ORDER BY a.created_at, a.file_name
                """, (rs, rowNum) -> new ReviewAttachmentView(
                uuid(rs, "id"),
                nullableUuid(rs, "answer_id"),
                rs.getString("question_path"),
                rs.getString("file_name"),
                rs.getString("content_type"),
                nullableLong(rs, "file_size"),
                rs.getString("content_hash"),
                instant(rs, "created_at")
        ), sessionId);

        return new SessionReviewView(session, answers, attachments);
    }

    private User requireCurrentWebUser() {
        return currentUserService.getCurrentWebUser()
                .orElseThrow(() -> new AccessDeniedException(
                        "Browser form workflow requires an authenticated web user"));
    }

    private static String normalizeStatus(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase();
        return switch (normalized) {
            case "draft", "active", "submitted" -> normalized;
            default -> throw new IllegalArgumentException(
                    "Unsupported web form session status: " + status);
        };
    }

    private static SessionSummaryView sessionSummary(ResultSet rs) throws SQLException {
        return new SessionSummaryView(
                uuid(rs, "session_id"),
                uuid(rs, "form_id"),
                rs.getString("form_title"),
                uuid(rs, "form_version_id"),
                rs.getString("form_version"),
                uuid(rs, "project_id"),
                rs.getString("project_name"),
                rs.getString("status"),
                instant(rs, "started_at"),
                instant(rs, "last_activity_at"),
                instant(rs, "completed_at"),
                instant(rs, "submitted_at"),
                nullableUuid(rs, "submission_id"),
                rs.getString("submission_instance_id")
        );
    }

    private static Object answerValue(ResultSet rs) throws SQLException {
        String text = rs.getString("value_text");
        if (text != null) return text;

        BigDecimal number = rs.getBigDecimal("value_number");
        if (number != null) return number;

        Timestamp date = rs.getTimestamp("value_date");
        if (date != null) return date.toInstant();

        Boolean bool = (Boolean) rs.getObject("value_boolean");
        if (bool != null) return bool;

        Object json = rs.getObject("value_json");
        if (json instanceof Clob clob) {
            return clob.getSubString(1, Math.toIntExact(clob.length()));
        }
        return json == null ? null : json.toString();
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof UUID id) return id;
        if (value == null) {
            throw new SQLException("Required UUID column was null: " + column);
        }
        return UUID.fromString(value.toString());
    }

    private static UUID nullableUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (value instanceof UUID id) return id;
        return UUID.fromString(value.toString());
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).intValue();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record AvailableFormView(
            UUID formId,
            String title,
            String description,
            UUID projectId,
            String projectName,
            UUID formVersionId,
            String version,
            Instant publishedAt) {
    }

    public record SessionSummaryView(
            UUID sessionId,
            UUID formId,
            String formTitle,
            UUID formVersionId,
            String formVersion,
            UUID projectId,
            String projectName,
            String status,
            Instant startedAt,
            Instant lastActivityAt,
            Instant completedAt,
            Instant submittedAt,
            UUID submissionId,
            String submissionInstanceId) {
    }

    public record ReviewAnswerView(
            UUID id,
            String questionPath,
            String instancePath,
            String questionName,
            String questionType,
            Object value,
            String repeatPath,
            Integer repeatIndex,
            boolean required,
            String validationStatus,
            String constraintMessage,
            Instant answeredAt) {
    }

    public record ReviewAttachmentView(
            UUID id,
            UUID answerId,
            String questionPath,
            String fileName,
            String contentType,
            Long fileSize,
            String contentHash,
            Instant createdAt) {
    }

    public record SessionReviewView(
            SessionSummaryView session,
            List<ReviewAnswerView> answers,
            List<ReviewAttachmentView> attachments) {
    }
}
