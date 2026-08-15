package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class WebFormAssignmentService {

    public static final String TARGET_USER = "USER";
    public static final String TARGET_ROLE = "ROLE";

    private static final List<String> ASSIGNABLE_ROLE_NAMES = List.of(
            "PROJECT_ADMIN",
            "PROJECT_MANAGER",
            "DATA_ANALYST",
            "DATA_COLLECTOR",
            "VIEWER"
    );

    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public List<WebFormAssignmentView> listAssignments(UUID projectId, UUID formId) {
        validateForm(projectId, formId);

        return jdbcTemplate.query("""
                SELECT
                    a.id,
                    a.project_id,
                    a.form_id,
                    a.target_type,
                    a.target_id,
                    a.assigned_at,
                    u.username,
                    u.email,
                    u.first_name,
                    u.last_name,
                    r.name AS role_name,
                    r.display_name AS role_display_name
                FROM web_form_assignments a
                LEFT JOIN users u
                    ON a.target_type = 'USER' AND u.id = a.target_id
                LEFT JOIN roles r
                    ON a.target_type = 'ROLE' AND r.id = a.target_id
                WHERE a.project_id = ?
                  AND a.form_id = ?
                  AND a.is_active = TRUE
                ORDER BY
                    CASE WHEN a.target_type = 'ROLE' THEN 0 ELSE 1 END,
                    COALESCE(r.display_name, r.name, u.first_name, u.username),
                    a.assigned_at
                """, (rs, rowNum) -> {
            String targetType = rs.getString("target_type");
            String targetName;
            String targetDetail;

            if (TARGET_USER.equals(targetType)) {
                targetName = displayName(
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("username"));
                targetDetail = rs.getString("email");
            } else {
                targetName = firstNonBlank(
                        rs.getString("role_display_name"),
                        rs.getString("role_name"));
                targetDetail = rs.getString("role_name");
            }

            Timestamp assignedAt = rs.getTimestamp("assigned_at");

            return new WebFormAssignmentView(
                    rs.getObject("id", UUID.class),
                    rs.getObject("project_id", UUID.class),
                    rs.getObject("form_id", UUID.class),
                    targetType,
                    rs.getObject("target_id", UUID.class),
                    targetName,
                    targetDetail,
                    assignedAt == null ? null : assignedAt.toInstant()
            );
        }, projectId, formId);
    }

    @Transactional(readOnly = true)
    public List<UserCandidate> searchUserCandidates(UUID projectId, UUID formId, String query) {
        validateForm(projectId, formId);
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT
                    u.id,
                    u.username,
                    u.email,
                    u.first_name,
                    u.last_name
                FROM project_members pm
                JOIN users u ON u.id = pm.user_id
                WHERE pm.project_id = ?
                  AND pm.is_active = TRUE
                  AND u.is_active = TRUE
                """);

        List<Object> args = new ArrayList<>();
        args.add(projectId);

        if (!normalized.isBlank()) {
            sql.append("""
                    AND (
                        LOWER(u.username) LIKE ?
                        OR LOWER(u.email) LIKE ?
                        OR LOWER(COALESCE(u.first_name, '')) LIKE ?
                        OR LOWER(COALESCE(u.last_name, '')) LIKE ?
                        OR LOWER(COALESCE(u.first_name, '') || ' ' || COALESCE(u.last_name, '')) LIKE ?
                    )
                    """);
            String like = "%" + normalized + "%";
            for (int i = 0; i < 5; i++) {
                args.add(like);
            }
        }

        sql.append("""
                ORDER BY u.username
                FETCH FIRST 50 ROWS ONLY
                """);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new UserCandidate(
                rs.getObject("id", UUID.class),
                rs.getString("username"),
                rs.getString("email"),
                displayName(
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("username"))
        ), args.toArray());
    }

    @Transactional(readOnly = true)
    public List<RoleCandidate> listRoleCandidates(UUID projectId, UUID formId) {
        validateForm(projectId, formId);

        String placeholders = String.join(",", ASSIGNABLE_ROLE_NAMES.stream().map(name -> "?").toList());
        String sql = """
                SELECT id, name, display_name, description
                FROM roles
                WHERE is_active = TRUE
                  AND name IN (%s)
                ORDER BY CASE name
                    WHEN 'PROJECT_ADMIN' THEN 1
                    WHEN 'PROJECT_MANAGER' THEN 2
                    WHEN 'DATA_ANALYST' THEN 3
                    WHEN 'DATA_COLLECTOR' THEN 4
                    WHEN 'VIEWER' THEN 5
                    ELSE 99
                END, name
                """.formatted(placeholders);

        return jdbcTemplate.query(sql, (rs, rowNum) -> new RoleCandidate(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("display_name"),
                rs.getString("description")
        ), ASSIGNABLE_ROLE_NAMES.toArray(new Object[0]));
    }

    public WebFormAssignmentView assignUser(
            UUID projectId,
            UUID formId,
            UUID userId,
            UUID assignedBy) {
        validateForm(projectId, formId);
        validateProjectMember(projectId, userId);
        return assign(projectId, formId, TARGET_USER, userId, assignedBy);
    }

    public WebFormAssignmentView assignRole(
            UUID projectId,
            UUID formId,
            UUID roleId,
            UUID assignedBy) {
        validateForm(projectId, formId);
        validateAssignableRole(roleId);
        return assign(projectId, formId, TARGET_ROLE, roleId, assignedBy);
    }

    public void removeAssignment(UUID projectId, UUID formId, UUID assignmentId) {
        validateForm(projectId, formId);

        int changed = jdbcTemplate.update("""
                UPDATE web_form_assignments
                SET is_active = FALSE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND project_id = ?
                  AND form_id = ?
                  AND is_active = TRUE
                """, assignmentId, projectId, formId);

        if (changed == 0) {
            throw new IllegalArgumentException("Active web form assignment not found: " + assignmentId);
        }
    }

    @Transactional(readOnly = true)
    public Optional<WebFormAssignmentView> findActiveAssignment(
            UUID projectId,
            UUID formId,
            UUID assignmentId) {
        return listAssignments(projectId, formId).stream()
                .filter(item -> item.id().equals(assignmentId))
                .findFirst();
    }

    @Transactional(readOnly = true)
    public boolean hasAnyAssignments(UUID projectId, UUID formId) {
        validateForm(projectId, formId);
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM web_form_assignments
                WHERE project_id = ?
                  AND form_id = ?
                  AND is_active = TRUE
                """, Long.class, projectId, formId);
        return count != null && count > 0;
    }

    @Transactional(readOnly = true)
    public boolean hasAssignmentConfiguration(UUID projectId, UUID formId) {
        // Runtime callers already resolved the FormVersion/XForm through JPA.
        // Do not revalidate through JdbcTemplate here: a newly-persisted form
        // may still be unflushed inside the surrounding transaction.
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM web_form_assignments
                WHERE project_id = ?
                  AND form_id = ?
                """, Long.class, projectId, formId);

        return count != null && count > 0;
    }

    @Transactional(readOnly = true)
    public boolean isProjectParticipant(UUID projectId, UUID userId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM projects p
                WHERE p.id = ?
                  AND (
                      p.owner_id = ?
                      OR p.created_by_id = ?
                      OR EXISTS (
                          SELECT 1
                          FROM project_members pm
                          WHERE pm.project_id = p.id
                            AND pm.user_id = ?
                            AND pm.is_active = TRUE
                      )
                  )
                """, Long.class, projectId, userId, userId, userId);

        return count != null && count > 0;
    }

    @Transactional(readOnly = true)
    public boolean canManageProjectForms(UUID projectId, UUID userId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM projects p
                WHERE p.id = ?
                  AND (
                      p.owner_id = ?
                      OR p.created_by_id = ?
                      OR EXISTS (
                          SELECT 1
                          FROM project_members pm
                          JOIN roles r ON r.id = pm.role_id
                          WHERE pm.project_id = p.id
                            AND pm.user_id = ?
                            AND pm.is_active = TRUE
                            AND r.is_active = TRUE
                            AND r.name IN ('PROJECT_ADMIN', 'PROJECT_MANAGER')
                      )
                  )
                """, Long.class, projectId, userId, userId, userId);

        return count != null && count > 0;
    }

    /**
     * Resolves explicit web access for a project member.
     *
     * Access is granted by either:
     *  - an active direct USER assignment; or
     *  - an active ROLE assignment matching one of the user's active project roles.
     *
     * This method intentionally does not implement administrator bypass rules.
     * The web runtime can apply those rules separately when assignment enforcement
     * is wired into the session-start authorization path.
     */
    @Transactional(readOnly = true)
    public boolean hasUserAccess(UUID projectId, UUID formId, UUID userId) {
        // Runtime callers already validated the form context. Keeping this
        // check query-only avoids mixing an unflushed JPA form with a second
        // JDBC existence check in the same transaction.
        Long direct = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM web_form_assignments
                WHERE project_id = ?
                  AND form_id = ?
                  AND target_type = 'USER'
                  AND target_id = ?
                  AND is_active = TRUE
                """, Long.class, projectId, formId, userId);

        if (direct != null && direct > 0) {
            return true;
        }

        Long byRole = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM web_form_assignments a
                WHERE a.project_id = ?
                  AND a.form_id = ?
                  AND a.target_type = 'ROLE'
                  AND a.is_active = TRUE
                  AND EXISTS (
                      SELECT 1
                      FROM project_members pm
                      WHERE pm.project_id = ?
                        AND pm.user_id = ?
                        AND pm.role_id = a.target_id
                        AND pm.is_active = TRUE
                  )
                """, Long.class, projectId, formId, projectId, userId);

        return byRole != null && byRole > 0;
    }

    private WebFormAssignmentView assign(
            UUID projectId,
            UUID formId,
            String targetType,
            UUID targetId,
            UUID assignedBy) {

        Optional<UUID> existingId = jdbcTemplate.query("""
                SELECT id
                FROM web_form_assignments
                WHERE project_id = ?
                  AND form_id = ?
                  AND target_type = ?
                  AND target_id = ?
                """, rs -> rs.next()
                ? Optional.of(rs.getObject("id", UUID.class))
                : Optional.empty(),
                projectId, formId, targetType, targetId);

        UUID assignmentId;
        if (existingId.isPresent()) {
            assignmentId = existingId.get();
            jdbcTemplate.update("""
                    UPDATE web_form_assignments
                    SET is_active = TRUE,
                        assigned_by = ?,
                        assigned_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, assignedBy, assignmentId);
        } else {
            assignmentId = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO web_form_assignments (
                        id,
                        project_id,
                        form_id,
                        target_type,
                        target_id,
                        assigned_by,
                        is_active,
                        assigned_at,
                        created_at,
                        updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    assignmentId,
                    projectId,
                    formId,
                    targetType,
                    targetId,
                    assignedBy);
        }

        return findActiveAssignment(projectId, formId, assignmentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Web form assignment was saved but could not be reloaded: " + assignmentId));
    }

    private void validateForm(UUID projectId, UUID formId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM xforms
                WHERE id = ?
                  AND project_id = ?
                """, Long.class, formId, projectId);
        if (count == null || count == 0) {
            throw new IllegalArgumentException(
                    "Form " + formId + " does not belong to project " + projectId);
        }
    }

    private void validateProjectMember(UUID projectId, UUID userId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM project_members pm
                JOIN users u ON u.id = pm.user_id
                WHERE pm.project_id = ?
                  AND pm.user_id = ?
                  AND pm.is_active = TRUE
                  AND u.is_active = TRUE
                """, Long.class, projectId, userId);
        if (count == null || count == 0) {
            throw new IllegalArgumentException(
                    "User " + userId + " is not an active member of project " + projectId);
        }
    }

    private void validateAssignableRole(UUID roleId) {
        String placeholders = String.join(",", ASSIGNABLE_ROLE_NAMES.stream().map(name -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(roleId);
        args.addAll(ASSIGNABLE_ROLE_NAMES);

        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM roles
                WHERE id = ?
                  AND is_active = TRUE
                  AND name IN (%s)
                """.formatted(placeholders), Long.class, args.toArray());

        if (count == null || count == 0) {
            throw new IllegalArgumentException("Role is not assignable to a project form: " + roleId);
        }
    }

    private static String displayName(String firstName, String lastName, String fallback) {
        String combined = ((firstName == null ? "" : firstName.trim()) + " "
                + (lastName == null ? "" : lastName.trim())).trim();
        return combined.isBlank() ? fallback : combined;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    public record WebFormAssignmentView(
            UUID id,
            UUID projectId,
            UUID formId,
            String targetType,
            UUID targetId,
            String targetName,
            String targetDetail,
            Instant assignedAt) {
    }

    public record UserCandidate(
            UUID id,
            String username,
            String email,
            String fullName) {
    }

    public record RoleCandidate(
            UUID id,
            String name,
            String displayName,
            String description) {
    }
}
