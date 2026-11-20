package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * M3D4B1: separate web reviewer eligibility, NOT approval issuance.
 *
 * Web reviewer eligibility must never be inferred from a mobile enumerator
 * role, a client-supplied reviewer ID or local Android mapping decisions.
 */
@Service
@RequiredArgsConstructor
public class TypedMappingReviewerEligibilityService {
    private final JdbcTemplate jdbc;

    public record ReviewerEligibility(
            UUID projectId, UUID workId, UUID reviewerId,
            String state, boolean eligibleToRequestIndependentReview,
            boolean canApproveMapping, boolean canTransfer, boolean canSubmit
    ) {
        public ReviewerEligibility {
            if (canApproveMapping || canTransfer || canSubmit) {
                throw new IllegalArgumentException(
                    "Web reviewer eligibility is not a server-issued mapping grant");
            }
        }
    }

    @Transactional(readOnly = true)
    public ReviewerEligibility inspect(UUID projectId, UUID workId, String authHeader) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // The web UI's real Bearer/JWT principal must be a Web User.
        // Never treat a mobile AppUser Basic login or a username collision as a reviewer.
        if (authHeader == null || !authHeader.startsWith("Bearer ") ||
                authentication == null || !authentication.isAuthenticated() ||
                !(authentication.getPrincipal() instanceof UserDetails principal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Independent web reviewer identity required");
        }
        // The JWT filter intentionally stores a Spring Security UserDetails,
        // NOT the JPA User entity. Resolve current web identity from the DB.
        // Imported web/mobile username collisions are denied: JWT subjects
        // currently contain a username, not a principal namespace.
        List<UUID> currentWebUser = jdbc.query("""
                SELECT u.id FROM users u
                WHERE u.username = ? AND u.is_active = TRUE
                  AND u.is_verified = TRUE
                  AND NOT EXISTS (
                      SELECT 1 FROM app_users a
                      WHERE LOWER(a.username) = LOWER(u.username)
                         OR LOWER(a.email) = LOWER(u.email)
                  )
                """, (rs, idx) -> rs.getObject("id", UUID.class),
                principal.getUsername());
        if (currentWebUser.size() != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Independent web reviewer identity required");
        }
        UUID reviewerId = currentWebUser.get(0);

        // A current database check is mandatory: an old JWT must not retain
        // review eligibility after deactivation, loss of project membership or
        // project-role revocation. No global ADMIN bypass is allowed.
        Integer authorized = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project_members pm
                JOIN roles r ON r.id = pm.role_id
                JOIN users u ON u.id = pm.user_id
                JOIN projects p ON p.id = pm.project_id
                WHERE pm.project_id = ? AND pm.user_id = ?
                  AND pm.is_active = TRUE AND r.is_active = TRUE
                  AND r.name IN ('PROJECT_MANAGER', 'PROJECT_ADMIN')
                  AND (r.project_id IS NULL OR r.project_id = pm.project_id)
                  AND u.is_active = TRUE AND u.is_verified = TRUE
                  AND p.is_active = TRUE
                """, Integer.class, projectId, reviewerId);
        if (authorized == null || authorized < 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Eligible project reviewer not found");
        }

        // Match only a READY amendment, with the exact original source form
        // association and persisted original/target XForm definitions. Never
        // reveal source answers, protected work ownership or form XML.
        //
        // Prevent obvious same-person identities reused between web/mobile
        // accounts by matching both username and email; stronger human identity
        // verification and a second independent approver remain future gates.
        List<ReviewerEligibility> allowed = jdbc.query("""
                SELECT w.id, w.project_id
                FROM record_work_items w
                JOIN submissions src ON src.id = w.source_submission_id
                JOIN form_versions old_form ON old_form.id = w.source_form_version_id
                JOIN form_versions target ON target.id = w.target_form_version_id
                JOIN xforms sf ON sf.id = src.form_id
                JOIN app_users enumerator ON enumerator.id = w.assigned_app_user_id
                JOIN users reviewer ON reviewer.id = ?
                WHERE w.project_id = ? AND w.id = ?
                  AND w.work_type = 'AMENDMENT' AND w.state = 'READY'
                  AND w.source_instance_id = src.instance_id
                  AND src.form_version_id = old_form.id
                  AND old_form.form_id = src.form_id
                  AND target.form_id = src.form_id
                  AND sf.project_id = w.project_id
                  AND old_form.xml_content IS NOT NULL
                  AND LENGTH(TRIM(old_form.xml_content)) > 0
                  AND target.xml_content IS NOT NULL
                  AND LENGTH(TRIM(target.xml_content)) > 0
                  AND target.is_published = TRUE AND sf.is_active = TRUE
                  AND sf.is_published = TRUE
                  AND LOWER(reviewer.username) <> LOWER(enumerator.username)
                  AND LOWER(reviewer.email) <> LOWER(enumerator.email)
                """, (rs, idx) -> new ReviewerEligibility(
                    projectId, workId, reviewerId,
                    "ELIGIBLE_TO_REQUEST_REVIEW_ONLY", true,
                    false, false, false),
                reviewerId, projectId, workId);
        if (allowed.isEmpty()) {
            // Suppress work existence and whether identity/record/schema or
            // publication failed; this is not an answer access endpoint.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Eligible mapping review not found");
        }
        return allowed.get(0);
    }
}
