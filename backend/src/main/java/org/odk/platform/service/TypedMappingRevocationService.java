package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M3D4C2B1: authenticated server-owned, write-once REVOKE operations only.
 * There is no key enrolment, grant issuance, revival, transfer or upload here.
 *
 * Operational grant issuance needs independent policy/consent/key ceremonies,
 * an anchored audit, and use-stage enforcement; tombstones alone aren't enough.
 */
@Service
@RequiredArgsConstructor
public class TypedMappingRevocationService {
    private final JdbcTemplate jdbc;

    public enum GrantReason {
        SECURITY, CONSENT_WITHDRAWN, SCHEMA_DRIFT,
        WORK_CANCELLED, SOURCE_CHANGED, SIGNER_REVOKED
    }
    public enum KeyReason {
        KEY_COMPROMISE, KEY_ROTATION, SECURITY, OPERATOR_REQUEST
    }

    public record GrantRevocationRequest(GrantReason reasonCode) {}
    public record KeyRevocationRequest(KeyReason reasonCode) {}

    public record RevocationReceipt(
            UUID projectId, String subjectType, String subjectId,
            UUID revokedBy, String reasonCode, Instant revokedAt,
            String state, boolean canReadHistoricalAnswers,
            boolean canTransfer, boolean canFinalize, boolean canSubmit) {
        public RevocationReceipt {
            if (projectId == null || subjectId == null || revokedBy == null ||
                    revokedAt == null || canReadHistoricalAnswers || canTransfer ||
                    canFinalize || canSubmit) {
                throw new IllegalArgumentException(
                    "Revocation cannot confer historical record permissions");
            }
        }
    }

    private record Existing(UUID actor, String reason, Instant at) {}
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    /**
     * Revocation intentionally does NOT depend on READY state or unexpired
     * signer permissions. Emergency revocation must remain possible even
     * after work cancellation, schema drift or signer identity deactivation.
     */
    @Transactional
    public RevocationReceipt revokeGrant(
            UUID projectId, UUID grantId, String authorization,
            GrantRevocationRequest request) {
        if (request == null || request.reasonCode() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An explicit grant revocation reason is required");
        }
        UUID actor = currentProjectAdmin(projectId, authorization);
        List<UUID> locked = jdbc.query("""
                SELECT grant_id FROM typed_mapping_signed_grant_registry
                WHERE grant_id = ? AND project_id = ? FOR UPDATE
                """, (rs, row) -> rs.getObject("grant_id", UUID.class),
                grantId, projectId);
        if (locked.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Registered grant not found in project");
        }
        List<Existing> prior = jdbc.query("""
                SELECT revoked_by, reason, revoked_at
                FROM typed_mapping_grant_revocations WHERE grant_id = ?
                """, (rs, row) -> new Existing(
                    rs.getObject("revoked_by", UUID.class),
                    rs.getString("reason"),
                    rs.getTimestamp("revoked_at").toInstant()), grantId);
        if (!prior.isEmpty()) {
            return replay(projectId, "GRANT", grantId.toString(),
                    actor, request.reasonCode().name(), prior.get(0));
        }
        try {
            jdbc.update("""
                    INSERT INTO typed_mapping_grant_revocations
                    (grant_id, revoked_by, reason) VALUES (?, ?, ?)
                    """, grantId, actor, request.reasonCode().name());
        } catch (DuplicateKeyException concurrent) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Grant revocation already recorded", concurrent);
        }
        return receipt(projectId, "GRANT", grantId.toString(),
                actor, request.reasonCode().name(),
                storedGrant(grantId).at());
    }

    @Transactional
    public RevocationReceipt revokeKey(
            UUID projectId, String keyId, String authorization,
            KeyRevocationRequest request) {
        if (request == null || request.reasonCode() == null ||
                keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A valid project signing key and revocation reason are required");
        }
        UUID actor = currentProjectAdmin(projectId, authorization);
        List<String> locked = jdbc.query("""
                SELECT key_id FROM typed_mapping_trust_keys
                WHERE key_id = ? AND project_id = ? FOR UPDATE
                """, (rs, row) -> rs.getString("key_id"), keyId, projectId);
        if (locked.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Registered signing key not found in project");
        }
        List<Existing> prior = jdbc.query("""
                SELECT revoked_by, reason, revoked_at
                FROM typed_mapping_key_revocations WHERE key_id = ?
                """, (rs, row) -> new Existing(
                    rs.getObject("revoked_by", UUID.class),
                    rs.getString("reason"),
                    rs.getTimestamp("revoked_at").toInstant()), keyId);
        if (!prior.isEmpty()) {
            return replay(projectId, "KEY", keyId,
                    actor, request.reasonCode().name(), prior.get(0));
        }
        try {
            jdbc.update("""
                    INSERT INTO typed_mapping_key_revocations
                    (key_id, revoked_by, reason) VALUES (?, ?, ?)
                    """, keyId, actor, request.reasonCode().name());
        } catch (DuplicateKeyException concurrent) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Key revocation already recorded", concurrent);
        }
        return receipt(projectId, "KEY", keyId, actor,
                request.reasonCode().name(), storedKey(keyId).at());
    }

    private Existing storedGrant(UUID grantId) {
        return jdbc.queryForObject("""
                SELECT revoked_by, reason, revoked_at
                FROM typed_mapping_grant_revocations WHERE grant_id = ?
                """, (rs, row) -> new Existing(
                rs.getObject("revoked_by", UUID.class),
                rs.getString("reason"),
                rs.getTimestamp("revoked_at").toInstant()), grantId);
    }

    private Existing storedKey(String keyId) {
        return jdbc.queryForObject("""
                SELECT revoked_by, reason, revoked_at
                FROM typed_mapping_key_revocations WHERE key_id = ?
                """, (rs, row) -> new Existing(
                rs.getObject("revoked_by", UUID.class),
                rs.getString("reason"),
                rs.getTimestamp("revoked_at").toInstant()), keyId);
    }

    private RevocationReceipt replay(
            UUID projectId, String kind, String id,
            UUID actor, String reason, Existing prior) {
        if (!prior.actor().equals(actor) || !prior.reason().equals(reason)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Revocation is permanent and cannot be rewritten");
        }
        return receipt(projectId, kind, id, prior.actor(),
                prior.reason(), prior.at());
    }

    private static RevocationReceipt receipt(
            UUID projectId, String kind, String id, UUID actor,
            String reason, Instant at) {
        return new RevocationReceipt(projectId, kind, id, actor, reason, at,
                "REVOKED_PERMANENT_NO_GRANT", false, false, false, false);
    }

    /**
     * Resolve freshly from JWT UserDetails and current SQL; no client-provided
     * user ID, global admin override, or mobile Basic auth. No READY-work gate:
     * disabling a bad key must still be possible when every work is cancelled.
     */
    private UUID currentProjectAdmin(UUID projectId, String authorization) {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (authorization == null || !authorization.startsWith("Bearer ") ||
                authentication == null || !authentication.isAuthenticated() ||
                !(authentication.getPrincipal() instanceof UserDetails principal) ||
                !principal.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Authenticated web project administrator required");
        }
        List<UUID> authorized = jdbc.query("""
                SELECT DISTINCT user_record.id FROM users user_record
                JOIN project_members member ON member.user_id = user_record.id
                JOIN roles role ON role.id = member.role_id
                JOIN projects project ON project.id = member.project_id
                WHERE user_record.username = ?
                  AND project.id = ? AND project.is_active = TRUE
                  AND user_record.is_active = TRUE AND user_record.is_verified = TRUE
                  AND member.is_active = TRUE AND role.is_active = TRUE
                  AND role.name = 'PROJECT_ADMIN'
                  AND (role.project_id IS NULL OR role.project_id = project.id)
                  AND NOT EXISTS (
                      SELECT 1 FROM app_users mobile
                      WHERE LOWER(mobile.username) = LOWER(user_record.username)
                         OR LOWER(mobile.email) = LOWER(user_record.email)
                  )
                """, (rs, row) -> rs.getObject("id", UUID.class),
                principal.getUsername(), projectId);
        if (authorized.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Project revocation administrator unavailable");
        }
        return authorized.get(0);
    }
}
