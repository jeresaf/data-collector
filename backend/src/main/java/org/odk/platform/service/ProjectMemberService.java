package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.odk.platform.audit.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ProjectMemberService {

    private static final Set<String> ASSIGNABLE_PROJECT_ROLES = Set.of(
            "PROJECT_ADMIN",
            "PROJECT_MANAGER",
            "DATA_ANALYST",
            "DATA_COLLECTOR",
            "VIEWER"
    );

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<ProjectMemberView> listMembers(UUID projectId) {
        requireProject(projectId);

        return jdbcTemplate.query("""
                SELECT pm.id,
                       pm.project_id,
                       pm.user_id,
                       u.username,
                       u.email,
                       u.first_name,
                       u.last_name,
                       pm.role_id,
                       r.name AS role_name,
                       r.display_name AS role_display_name,
                       pm.created_at
                  FROM project_members pm
                  JOIN users u ON u.id = pm.user_id
                  JOIN roles r ON r.id = pm.role_id
                 WHERE pm.project_id = ?
                   AND pm.is_active = TRUE
                 ORDER BY LOWER(COALESCE(u.first_name, '')),
                          LOWER(COALESCE(u.last_name, '')),
                          LOWER(u.username),
                          LOWER(r.name)
                """, this::mapMember, projectId);
    }

    @Transactional(readOnly = true)
    public List<UserCandidate> searchCandidates(UUID projectId, String query) {
        requireProject(projectId);

        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String pattern = "%" + normalized + "%";

        return jdbcTemplate.query("""
                SELECT u.id,
                       u.username,
                       u.email,
                       u.first_name,
                       u.last_name
                  FROM users u
                 WHERE u.is_active = TRUE
                   AND (
                        LOWER(u.username) LIKE ?
                        OR LOWER(u.email) LIKE ?
                        OR LOWER(COALESCE(u.first_name, '')) LIKE ?
                        OR LOWER(COALESCE(u.last_name, '')) LIKE ?
                        OR LOWER(
                            TRIM(
                                COALESCE(u.first_name, '') || ' ' ||
                                COALESCE(u.last_name, '')
                            )
                        ) LIKE ?
                   )
                 ORDER BY LOWER(COALESCE(u.first_name, '')),
                          LOWER(COALESCE(u.last_name, '')),
                          LOWER(u.username)
                 LIMIT 50
                """, this::mapCandidate,
                pattern, pattern, pattern, pattern, pattern);
    }

    @Transactional(readOnly = true)
    public List<ProjectRoleOption> listAssignableRoles() {
        List<String> roleNames = new ArrayList<>(ASSIGNABLE_PROJECT_ROLES);

        String placeholders = String.join(",", roleNames.stream().map(ignored -> "?").toList());

        return jdbcTemplate.query(
                """
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
                """.formatted(placeholders),
                this::mapRole,
                roleNames.toArray()
        );
    }

    public ProjectMemberView addMember(
            UUID projectId,
            UUID userId,
            UUID roleId,
            UUID assignedBy) {

        requireProject(projectId);
        UserCandidate user = requireActiveUser(userId);
        ProjectRoleOption role = requireAssignableRole(roleId);

        List<UUID> existingIds = jdbcTemplate.query(
                """
                SELECT id
                  FROM project_members
                 WHERE project_id = ?
                   AND user_id = ?
                   AND role_id = ?
                """,
                (rs, rowNum) -> uuid(rs, "id"),
                projectId, userId, roleId
        );

        UUID memberId;
        boolean reactivated;

        if (existingIds.isEmpty()) {
            memberId = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO project_members (
                        id,
                        project_id,
                        user_id,
                        role_id,
                        assigned_by,
                        is_active,
                        created_at,
                        updated_at
                    ) VALUES (?, ?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    memberId,
                    projectId,
                    userId,
                    roleId,
                    assignedBy
            );
            reactivated = false;
        } else {
            memberId = existingIds.get(0);
            jdbcTemplate.update("""
                    UPDATE project_members
                       SET is_active = TRUE,
                           assigned_by = ?,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE id = ?
                    """,
                    assignedBy,
                    memberId
            );
            reactivated = true;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("projectId", projectId.toString());
        details.put("userId", userId.toString());
        details.put("username", user.username());
        details.put("roleId", roleId.toString());
        details.put("roleName", role.name());
        details.put("reactivated", reactivated);

        auditService.log(
                "PROJECT_MEMBER_ASSIGNED",
                "PROJECT_MEMBER",
                memberId,
                user.username(),
                details
        );

        return findActiveMember(projectId, memberId);
    }

    public void removeMember(UUID projectId, UUID memberId) {
        ProjectMemberView member = findActiveMember(projectId, memberId);

        int updated = jdbcTemplate.update("""
                UPDATE project_members
                   SET is_active = FALSE,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                   AND project_id = ?
                   AND is_active = TRUE
                """,
                memberId,
                projectId
        );

        if (updated == 0) {
            throw new IllegalArgumentException("Project member not found: " + memberId);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("projectId", projectId.toString());
        details.put("userId", member.userId().toString());
        details.put("username", member.username());
        details.put("roleId", member.roleId().toString());
        details.put("roleName", member.roleName());

        auditService.log(
                "PROJECT_MEMBER_REMOVED",
                "PROJECT_MEMBER",
                memberId,
                member.username(),
                details
        );
    }

    @Transactional(readOnly = true)
    public ProjectMemberView findActiveMember(UUID projectId, UUID memberId) {
        requireProject(projectId);

        List<ProjectMemberView> rows = jdbcTemplate.query("""
                SELECT pm.id,
                       pm.project_id,
                       pm.user_id,
                       u.username,
                       u.email,
                       u.first_name,
                       u.last_name,
                       pm.role_id,
                       r.name AS role_name,
                       r.display_name AS role_display_name,
                       pm.created_at
                  FROM project_members pm
                  JOIN users u ON u.id = pm.user_id
                  JOIN roles r ON r.id = pm.role_id
                 WHERE pm.project_id = ?
                   AND pm.id = ?
                   AND pm.is_active = TRUE
                """,
                this::mapMember,
                projectId,
                memberId
        );

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Project member not found: " + memberId);
        }
        return rows.get(0);
    }

    private void requireProject(UUID projectId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM projects WHERE id = ?",
                Long.class,
                projectId
        );
        if (count == null || count == 0) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }
    }

    private UserCandidate requireActiveUser(UUID userId) {
        List<UserCandidate> rows = jdbcTemplate.query("""
                SELECT id, username, email, first_name, last_name
                  FROM users
                 WHERE id = ?
                   AND is_active = TRUE
                """,
                this::mapCandidate,
                userId
        );

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Active user not found: " + userId);
        }
        return rows.get(0);
    }

    private ProjectRoleOption requireAssignableRole(UUID roleId) {
        List<ProjectRoleOption> rows = jdbcTemplate.query("""
                SELECT id, name, display_name, description
                  FROM roles
                 WHERE id = ?
                   AND is_active = TRUE
                """,
                this::mapRole,
                roleId
        );

        if (rows.isEmpty() || !ASSIGNABLE_PROJECT_ROLES.contains(rows.get(0).name())) {
            throw new IllegalArgumentException("Assignable project role not found: " + roleId);
        }
        return rows.get(0);
    }

    private ProjectMemberView mapMember(ResultSet rs, int rowNum) throws SQLException {
        return new ProjectMemberView(
                uuid(rs, "id"),
                uuid(rs, "project_id"),
                uuid(rs, "user_id"),
                rs.getString("username"),
                rs.getString("email"),
                fullName(
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("username")
                ),
                uuid(rs, "role_id"),
                rs.getString("role_name"),
                rs.getString("role_display_name"),
                instant(rs.getTimestamp("created_at"))
        );
    }

    private UserCandidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        String username = rs.getString("username");
        return new UserCandidate(
                uuid(rs, "id"),
                username,
                rs.getString("email"),
                fullName(
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        username
                )
        );
    }

    private ProjectRoleOption mapRole(ResultSet rs, int rowNum) throws SQLException {
        return new ProjectRoleOption(
                uuid(rs, "id"),
                rs.getString("name"),
                rs.getString("display_name"),
                rs.getString("description")
        );
    }

    private UUID uuid(ResultSet rs, String column) throws SQLException {
        Object raw = rs.getObject(column);
        if (raw instanceof UUID value) {
            return value;
        }
        return UUID.fromString(String.valueOf(raw));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String fullName(String firstName, String lastName, String username) {
        String name = ((firstName == null ? "" : firstName.trim()) + " "
                + (lastName == null ? "" : lastName.trim())).trim();
        return name.isBlank() ? username : name;
    }

    public record ProjectMemberView(
            UUID id,
            UUID projectId,
            UUID userId,
            String username,
            String email,
            String fullName,
            UUID roleId,
            String roleName,
            String roleDisplayName,
            Instant assignedAt
    ) {
    }

    public record UserCandidate(
            UUID id,
            String username,
            String email,
            String fullName
    ) {
    }

    public record ProjectRoleOption(
            UUID id,
            String name,
            String displayName,
            String description
    ) {
    }
}
