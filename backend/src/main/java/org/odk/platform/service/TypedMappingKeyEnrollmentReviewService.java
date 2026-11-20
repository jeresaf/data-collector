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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M3D4C2B2A: TWO-PERSON REVIEW of UNTRUSTED Ed25519 public-key PROPOSALS.
 *
 * No method adds to typed_mapping_trust_keys or issues signing/grant authority.
 * Both the request and its independent decision are permanently non-authorizing.
 * A later externally controlled key ceremony and verified human identity are
 * mandatory before any operational issuer can exist.
 */
@Service
@RequiredArgsConstructor
public class TypedMappingKeyEnrollmentReviewService {
    private final JdbcTemplate jdbc;

    public enum Purpose {
        NEW_PROJECT_PILOT, ROTATION_REVIEW, COMPROMISE_REPLACEMENT
    }
    public enum Disposition {
        REJECTED, REQUIRES_OFFLINE_KEY_CEREMONY
    }

    public record ProposalRequest(
            String clientRequestId, String proposedKeyId,
            String publicKeySpkiBase64Url, Purpose purpose) {}
    public record ReviewRequest(
            Disposition disposition, String requestEvidenceSha256) {}

    public record ProposalReceipt(
            UUID requestId, UUID projectId, String proposedKeyId,
            UUID requesterId, String publicKeySha256,
            String requestEvidenceSha256, String state,
            boolean trustedKeyRegistered, boolean canIssueGrant,
            boolean canTransfer, boolean canSubmit) {
        public ProposalReceipt {
            if (trustedKeyRegistered || canIssueGrant || canTransfer || canSubmit) {
                throw new IllegalArgumentException("Key proposal cannot activate trust");
            }
        }
    }

    public record ReviewContext(
            UUID requestId, UUID projectId, String proposedKeyId,
            UUID originalRequesterId, UUID independentReviewerId,
            String publicKeySha256, String requestEvidenceSha256,
            String state, boolean trustedKeyRegistered,
            boolean canIssueGrant, boolean canTransfer, boolean canSubmit) {
        public ReviewContext {
            if (trustedKeyRegistered || canIssueGrant || canTransfer || canSubmit) {
                throw new IllegalArgumentException("Key review context cannot activate trust");
            }
        }
    }

    public record ReviewReceipt(
            UUID requestId, UUID projectId, String proposedKeyId,
            UUID requesterId, UUID reviewerId, Disposition disposition,
            String requestEvidenceSha256, String reviewEvidenceSha256,
            Instant reviewedAt, String state, boolean trustedKeyRegistered,
            boolean canIssueGrant, boolean canTransfer, boolean canSubmit) {
        public ReviewReceipt {
            if (trustedKeyRegistered || canIssueGrant || canTransfer || canSubmit) {
                throw new IllegalArgumentException("Key review cannot activate trust");
            }
        }
    }

    private record ProposalRow(
            UUID id, UUID projectId, String keyId, byte[] spki, String spkiHash,
            UUID requesterId, String requesterName, String requesterEmail,
            String clientRequestId, Purpose purpose, String evidenceHash) {}
    private record PriorReview(UUID reviewer, Disposition disposition,
                               String requestHash, String reviewHash,
                               Instant reviewedAt) {}

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    // ASN.1 SubjectPublicKeyInfo for id-Ed25519, then exactly 32 public bytes.
    private static final byte[] ED25519_SPKI_HEADER =
            HexFormat.of().parseHex("302a300506032b6570032100");
    private static final int MAX_PROJECT_REQUESTS = 10;

    @Transactional
    public ProposalReceipt propose(
            UUID projectId, String authorization, ProposalRequest request) {
        if (request == null || request.clientRequestId() == null ||
                !REQUEST_ID.matcher(request.clientRequestId()).matches() ||
                request.proposedKeyId() == null ||
                !KEY_ID.matcher(request.proposedKeyId()).matches() ||
                request.purpose() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bounded public-key proposal and purpose are required");
        }
        UUID actor = currentProjectRole(projectId, authorization, false);
        byte[] key = canonicalEd25519Spki(request.publicKeySpkiBase64Url());
        String keyHash = sha256(key);
        String expectedHash = requestDigest(projectId, actor,
                request.clientRequestId(), request.proposedKeyId(),
                request.purpose(), keyHash);

        // Project-scoped lock ensures the quota and idempotency are checked
        // atomically against other proposal writers. This never locks or
        // writes the active trusted-key registry.
        lockActiveProject(projectId);
        List<ProposalRow> prior = jdbc.query("""
                SELECT proposal.id, proposal.project_id, proposal.proposed_key_id,
                       proposal.public_key_spki, proposal.spki_sha256,
                       proposal.requester_user_id, person.username,
                       person.email, proposal.client_request_id,
                       proposal.purpose, proposal.request_evidence_sha256
                FROM typed_mapping_key_enrollment_requests proposal
                JOIN users person ON person.id = proposal.requester_user_id
                WHERE proposal.project_id = ? AND proposal.requester_user_id = ?
                  AND proposal.client_request_id = ?
                """, (rs, i) -> readProposal(rs), projectId, actor,
                request.clientRequestId());
        if (!prior.isEmpty()) {
            ProposalRow existing = prior.get(0);
            if (!existing.keyId().equals(request.proposedKeyId()) ||
                    !existing.purpose().equals(request.purpose()) ||
                    !Arrays.equals(key, existing.spki()) ||
                    !keyHash.equals(existing.spkiHash()) ||
                    !expectedHash.equals(existing.evidenceHash())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Enrollment idempotency key reused with changed content");
            }
            return proposalReceipt(existing);
        }
        if (keyAlreadyTrustedOrRevoked(request.proposedKeyId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Proposed key identifier already in trusted registry");
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM typed_mapping_key_enrollment_requests
                WHERE project_id = ? AND requester_user_id = ?
                """, Integer.class, projectId, actor);
        if (count == null || count >= MAX_PROJECT_REQUESTS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Public key enrollment review request limit reached");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO typed_mapping_key_enrollment_requests
                    (id, project_id, proposed_key_id, public_key_spki, spki_sha256,
                     requester_user_id, client_request_id, purpose,
                     request_evidence_sha256)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, projectId, request.proposedKeyId(), key, keyHash,
                    actor, request.clientRequestId(), request.purpose().name(),
                    expectedHash);
        } catch (DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Public key proposal is already registered for review", conflict);
        }
        return new ProposalReceipt(id, projectId, request.proposedKeyId(),
                actor, keyHash, expectedHash,
                "UNTRUSTED_PUBLIC_KEY_PROPOSAL_ONLY",
                false, false, false, false);
    }

    @Transactional(readOnly = true)
    public ReviewContext reviewContext(
            UUID projectId, UUID requestId, String authorization) {
        UUID reviewer = currentProjectRole(projectId, authorization, true);
        ProposalRow proposal = proposal(projectId, requestId);
        validateIndependentAndUntrusted(proposal, reviewer);
        return new ReviewContext(proposal.id(), projectId, proposal.keyId(),
                proposal.requesterId(), reviewer, proposal.spkiHash(),
                proposal.evidenceHash(),
                "ELIGIBLE_TO_REVIEW_UNTRUSTED_KEY_ONLY",
                false, false, false, false);
    }

    @Transactional
    public ReviewReceipt review(
            UUID projectId, UUID requestId, String authorization,
            ReviewRequest request) {
        if (request == null || request.disposition() == null ||
                request.requestEvidenceSha256() == null ||
                !SHA256.matcher(request.requestEvidenceSha256()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Independent decision and exact proposal hash required");
        }
        UUID reviewer = currentProjectRole(projectId, authorization, true);
        List<UUID> locked = jdbc.query("""
                SELECT id FROM typed_mapping_key_enrollment_requests
                WHERE project_id = ? AND id = ? FOR UPDATE
                """, (rs, i) -> rs.getObject("id", UUID.class),
                projectId, requestId);
        if (locked.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Public-key proposal unavailable in project");
        }
        ProposalRow proposal = proposal(projectId, requestId);
        validateIndependentAndUntrusted(proposal, reviewer);
        if (!proposal.evidenceHash().equals(request.requestEvidenceSha256())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Public-key proposal changed since independent review");
        }
        String digest = reviewDigest(proposal, reviewer, request.disposition());

        List<PriorReview> prior = jdbc.query("""
                SELECT reviewer_user_id, disposition, request_evidence_sha256,
                       review_evidence_sha256, reviewed_at
                FROM typed_mapping_key_enrollment_reviews WHERE request_id = ?
                """, (rs, i) -> new PriorReview(
                    rs.getObject("reviewer_user_id", UUID.class),
                    Disposition.valueOf(rs.getString("disposition")),
                    rs.getString("request_evidence_sha256"),
                    rs.getString("review_evidence_sha256"),
                    rs.getTimestamp("reviewed_at").toInstant()), requestId);
        if (!prior.isEmpty()) {
            PriorReview existing = prior.get(0);
            if (!existing.reviewer().equals(reviewer) ||
                    existing.disposition() != request.disposition() ||
                    !existing.requestHash().equals(proposal.evidenceHash()) ||
                    !existing.reviewHash().equals(digest)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Independent review decision cannot be overwritten");
            }
            return receipt(proposal, reviewer, request.disposition(),
                    digest, existing.reviewedAt());
        }
        try {
            jdbc.update("""
                    INSERT INTO typed_mapping_key_enrollment_reviews
                    (request_id, project_id, reviewer_user_id, disposition,
                     request_evidence_sha256, review_evidence_sha256)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, requestId, projectId, reviewer, request.disposition().name(),
                    proposal.evidenceHash(), digest);
        } catch (DuplicateKeyException concurrent) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Independent key review already recorded", concurrent);
        }
        Instant reviewedAt = jdbc.queryForObject("""
                SELECT reviewed_at FROM typed_mapping_key_enrollment_reviews
                WHERE request_id = ?
                """, (rs, i) -> rs.getTimestamp("reviewed_at").toInstant(),
                requestId);
        return receipt(proposal, reviewer, request.disposition(),
                digest, reviewedAt);
    }

    private void lockActiveProject(UUID projectId) {
        List<UUID> ids = jdbc.query("""
                SELECT id FROM projects WHERE id = ? AND is_active = TRUE
                FOR UPDATE
                """, (rs, i) -> rs.getObject("id", UUID.class), projectId);
        if (ids.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Active project unavailable");
        }
    }

    private ProposalRow proposal(UUID projectId, UUID requestId) {
        List<ProposalRow> rows = jdbc.query("""
                SELECT proposal.id, proposal.project_id, proposal.proposed_key_id,
                       proposal.public_key_spki, proposal.spki_sha256,
                       proposal.requester_user_id, person.username,
                       person.email, proposal.client_request_id,
                       proposal.purpose, proposal.request_evidence_sha256
                FROM typed_mapping_key_enrollment_requests proposal
                JOIN users person ON person.id = proposal.requester_user_id
                WHERE proposal.project_id = ? AND proposal.id = ?
                """, (rs, i) -> readProposal(rs), projectId, requestId);
        if (rows.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Public-key proposal unavailable in project");
        }
        return rows.get(0);
    }

    private static ProposalRow readProposal(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ProposalRow(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("proposed_key_id"),
                rs.getBytes("public_key_spki"),
                rs.getString("spki_sha256"),
                rs.getObject("requester_user_id", UUID.class),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("client_request_id"),
                Purpose.valueOf(rs.getString("purpose")),
                rs.getString("request_evidence_sha256"));
    }

    private void validateIndependentAndUntrusted(
            ProposalRow proposal, UUID reviewer) {
        if (proposal.requesterId().equals(reviewer)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A key proposer cannot independently review their own key");
        }
        Integer eligible = jdbc.queryForObject("""
                SELECT COUNT(*) FROM users requester
                JOIN users reviewer ON reviewer.id = ?
                WHERE requester.id = ?
                  AND requester.is_active = TRUE AND requester.is_verified = TRUE
                  AND LOWER(requester.username) <> LOWER(reviewer.username)
                  AND LOWER(requester.email) <> LOWER(reviewer.email)
                  AND EXISTS (
                      SELECT 1 FROM project_members pm
                      JOIN roles role ON role.id = pm.role_id
                      WHERE pm.project_id = ? AND pm.user_id = requester.id
                        AND pm.is_active = TRUE AND role.is_active = TRUE
                        AND role.name IN ('PROJECT_MANAGER','PROJECT_ADMIN')
                        AND (role.project_id IS NULL OR role.project_id = pm.project_id)
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM app_users mobile
                      WHERE LOWER(mobile.username) = LOWER(requester.username)
                         OR LOWER(mobile.email) = LOWER(requester.email)
                  )
                """, Integer.class, reviewer, proposal.requesterId(),
                proposal.projectId());
        if (eligible == null || eligible != 1 ||
                keyAlreadyTrustedOrRevoked(proposal.keyId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Independent enrollment review is no longer eligible");
        }
        if (proposal.spki() == null ||
                !proposal.spkiHash().equals(sha256(proposal.spki())) ||
                !proposal.evidenceHash().equals(requestDigest(
                    proposal.projectId(), proposal.requesterId(),
                    proposal.clientRequestId(), proposal.keyId(),
                    proposal.purpose(), proposal.spkiHash()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Untrusted public-key request evidence changed");
        }
        try {
            validateEd25519Bytes(proposal.spki());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Stored public-key proposal is invalid");
        }
    }

    private boolean keyAlreadyTrustedOrRevoked(String keyId) {
        Integer exists = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM typed_mapping_trust_keys
                        WHERE key_id = ?) +
                       (SELECT COUNT(*) FROM typed_mapping_key_revocations
                        WHERE key_id = ?)
                """, Integer.class, keyId, keyId);
        return exists == null || exists != 0;
    }

    private static ProposalReceipt proposalReceipt(ProposalRow row) {
        return new ProposalReceipt(row.id(), row.projectId(), row.keyId(),
                row.requesterId(), row.spkiHash(), row.evidenceHash(),
                "UNTRUSTED_PUBLIC_KEY_PROPOSAL_ONLY",
                false, false, false, false);
    }

    private static ReviewReceipt receipt(
            ProposalRow proposal, UUID reviewer, Disposition disposition,
            String hash, Instant at) {
        return new ReviewReceipt(proposal.id(), proposal.projectId(),
                proposal.keyId(), proposal.requesterId(), reviewer,
                disposition, proposal.evidenceHash(), hash, at,
                disposition == Disposition.REJECTED
                    ? "REJECTED_NO_TRUST"
                    : "OFFLINE_CEREMONY_REQUIRED_NO_TRUST",
                false, false, false, false);
    }

    private UUID currentProjectRole(
            UUID projectId, String authorization, boolean adminOnly) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (authorization == null || !authorization.startsWith("Bearer ") ||
                auth == null || !auth.isAuthenticated() ||
                !(auth.getPrincipal() instanceof UserDetails principal) ||
                !principal.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Current authenticated web project member required");
        }
        List<UUID> users = jdbc.query("""
                SELECT DISTINCT person.id FROM users person
                JOIN project_members member ON member.user_id = person.id
                JOIN roles role ON role.id = member.role_id
                JOIN projects project ON project.id = member.project_id
                WHERE person.username = ? AND project.id = ?
                  AND person.is_active = TRUE AND person.is_verified = TRUE
                  AND project.is_active = TRUE AND member.is_active = TRUE
                  AND role.is_active = TRUE
                  AND (role.project_id IS NULL OR role.project_id = project.id)
                  AND (role.name = 'PROJECT_ADMIN'
                       OR (? = FALSE AND role.name = 'PROJECT_MANAGER'))
                  AND NOT EXISTS (
                      SELECT 1 FROM app_users mobile
                      WHERE LOWER(mobile.username) = LOWER(person.username)
                         OR LOWER(mobile.email) = LOWER(person.email)
                  )
                """, (rs, i) -> rs.getObject("id", UUID.class),
                principal.getUsername(), projectId, adminOnly);
        if (users.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Eligible project web member unavailable");
        }
        return users.get(0);
    }

    private static byte[] canonicalEd25519Spki(String encoded) {
        if (encoded == null || !encoded.matches("[A-Za-z0-9_-]{59}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Canonical Ed25519 public key required");
        }
        try {
            byte[] data = Base64.getUrlDecoder().decode(encoded);
            validateEd25519Bytes(data);
            if (!Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(data).equals(encoded)) {
                throw new IllegalArgumentException("Noncanonical public key encoding");
            }
            return data;
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid Ed25519 public-key SPKI encoding");
        }
    }

    private static void validateEd25519Bytes(byte[] data) {
        if (data == null || data.length != 44 ||
                !Arrays.equals(Arrays.copyOfRange(data, 0,
                    ED25519_SPKI_HEADER.length), ED25519_SPKI_HEADER)) {
            throw new IllegalArgumentException("Not canonical Ed25519 SPKI");
        }
        try {
            PublicKey pub = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(data));
            if (!Arrays.equals(pub.getEncoded(), data)) {
                throw new IllegalArgumentException("Noncanonical Ed25519 public key");
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid Ed25519 public key", ex);
        }
    }

    private static String requestDigest(
            UUID project, UUID requester, String requestId,
            String keyId, Purpose purpose, String spkiHash) {
        return digest("DC_UNTRUSTED_ED25519_KEY_PROPOSAL_V1",
                project.toString(), requester.toString(), requestId,
                keyId, purpose.name(), spkiHash);
    }

    private static String reviewDigest(
            ProposalRow proposal, UUID reviewer, Disposition disposition) {
        return digest("DC_INDEPENDENT_KEY_REVIEW_V1",
                proposal.projectId().toString(), proposal.id().toString(),
                proposal.requesterId().toString(), reviewer.toString(),
                proposal.evidenceHash(), disposition.name());
    }

    private static String digest(String... fields) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] part = field.getBytes(StandardCharsets.UTF_8);
                md.update(new byte[]{
                    (byte)(part.length >>> 24),
                    (byte)(part.length >>> 16),
                    (byte)(part.length >>> 8),
                    (byte)part.length });
                md.update(part);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception missing) {
            throw new IllegalStateException("SHA-256 unavailable", missing);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception missing) {
            throw new IllegalStateException("SHA-256 unavailable", missing);
        }
    }
}
