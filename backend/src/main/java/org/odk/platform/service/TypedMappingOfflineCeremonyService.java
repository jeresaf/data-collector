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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M3D4C2B2B1: third-party WEB witness records a HASH commitment to an
 * independently held offline evidence packet. No trust-key registration,
 * signer custody verification, HSM proof or usable data-transfer grant.
 *
 * The packet hash is caller-supplied and NOT a verified signature/anchor.
 * No production key is activated even when three web accounts participate.
 */
@Service
@RequiredArgsConstructor
public class TypedMappingOfflineCeremonyService {
    private final JdbcTemplate jdbc;

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public record CommitRequest(
            String clientRequestId, String proposalEvidenceSha256,
            String reviewEvidenceSha256, String observedKeySha256,
            String externalBundleSha256, String externalReference) {}

    public record CeremonyContext(
            UUID projectId, UUID proposalId, String proposedKeyId,
            UUID proposerId, UUID reviewerId, UUID witnessId,
            String proposalEvidenceSha256, String reviewEvidenceSha256,
            String proposedKeySha256, String state, boolean trustedKeyRegistered,
            boolean canIssueGrant, boolean canTransfer, boolean canSubmit) {
        public CeremonyContext {
            if (trustedKeyRegistered || canIssueGrant || canTransfer || canSubmit) {
                throw new IllegalArgumentException("Witness context cannot register trust");
            }
        }
    }

    public record CeremonyPacket(
            UUID projectId, UUID proposalId, String proposedKeyId,
            UUID proposerId, UUID reviewerId, UUID witnessId,
            String clientRequestId,
            String proposalEvidenceSha256, String reviewEvidenceSha256,
            String observedKeySha256, String externalBundleSha256,
            String externalReference, String commitmentSha256, Instant recordedAt,
            String state, boolean independentlyAttested, boolean trustedKeyRegistered,
            boolean canIssueGrant, boolean canTransfer, boolean canSubmit) {
        public CeremonyPacket {
            if (independentlyAttested || trustedKeyRegistered ||
                    canIssueGrant || canTransfer || canSubmit) {
                throw new IllegalArgumentException(
                    "Hash commitment cannot become an attestation or a grant");
            }
        }
    }

    private record ReviewLink(
            UUID projectId, UUID proposalId, String keyId, byte[] spki,
            String keySha, UUID proposerId, String proposerName,
            String proposerEmail, String clientId, String purpose,
            String proposalHash, UUID reviewerId, String reviewerName,
            String reviewerEmail, String disposition, String reviewRequestHash,
            String reviewHash) {}

    private record Existing(
            UUID reviewerId, UUID witnessId, String requestId,
            String proposalHash, String reviewHash, String observedKeyHash,
            String bundleHash, String reference, String commitmentHash,
            Instant recordedAt) {}

    @Transactional(readOnly = true)
    public CeremonyContext context(
            UUID projectId, UUID proposalId, String bearer) {
        UUID witness = eligibleAdmin(projectId, bearer);
        ReviewLink link = reviewedProposal(projectId, proposalId);
        verifyEligible(link, witness);
        return new CeremonyContext(projectId, proposalId, link.keyId(),
                link.proposerId(), link.reviewerId(), witness,
                link.proposalHash(), link.reviewHash(), link.keySha(),
                "THIRD_PERSON_EVIDENCE_COMMITMENT_ONLY",
                false, false, false, false);
    }

    @Transactional
    public CeremonyPacket commit(
            UUID projectId, UUID proposalId, String bearer,
            CommitRequest request) {
        if (request == null || !validId(request.clientRequestId()) ||
                !validId(request.externalReference()) ||
                !validHash(request.proposalEvidenceSha256()) ||
                !validHash(request.reviewEvidenceSha256()) ||
                !validHash(request.observedKeySha256()) ||
                !validHash(request.externalBundleSha256())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bounded exact ceremony evidence hashes and reference required");
        }
        UUID witness = eligibleAdmin(projectId, bearer);
        List<UUID> locked = jdbc.query("""
                SELECT id FROM typed_mapping_key_enrollment_requests
                WHERE id = ? AND project_id = ? FOR UPDATE
                """, (rs, n) -> rs.getObject("id", UUID.class),
                proposalId, projectId);
        if (locked.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Offline key proposal is not available");
        }
        // Lock the completed second-person review as well. Every current
        // eligibility decision is rechecked under these database row locks.
        List<UUID> reviewLocks = jdbc.query("""
                SELECT request_id FROM typed_mapping_key_enrollment_reviews
                WHERE request_id = ? AND project_id = ? FOR UPDATE
                """, (rs, n) -> rs.getObject("request_id", UUID.class),
                proposalId, projectId);
        if (reviewLocks.size() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Independent offline-key review is not complete");
        }
        ReviewLink link = reviewedProposal(projectId, proposalId);
        verifyEligible(link, witness);
        if (!request.proposalEvidenceSha256().equals(link.proposalHash()) ||
                !request.reviewEvidenceSha256().equals(link.reviewHash()) ||
                !request.observedKeySha256().equals(link.keySha())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Offline key evidence does not match reviewed proposal");
        }
        String digest = canonicalDigest(link, witness, request);
        List<Existing> previous = existing(proposalId);
        if (!previous.isEmpty()) {
            Existing prior = previous.get(0);
            if (!prior.reviewerId().equals(link.reviewerId()) ||
                    !prior.witnessId().equals(witness) ||
                    !prior.requestId().equals(request.clientRequestId()) ||
                    !prior.proposalHash().equals(link.proposalHash()) ||
                    !prior.reviewHash().equals(link.reviewHash()) ||
                    !prior.observedKeyHash().equals(link.keySha()) ||
                    !prior.bundleHash().equals(request.externalBundleSha256()) ||
                    !prior.reference().equals(request.externalReference()) ||
                    !prior.commitmentHash().equals(digest)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ceremony evidence has a different committed witness or intent");
            }
            return packet(link, prior);
        }
        try {
            jdbc.update("""
                    INSERT INTO typed_mapping_key_ceremony_commitments
                    (request_id, project_id, proposal_reviewer_user_id,
                     witness_user_id, client_request_id, proposal_evidence_sha256,
                     reviewer_evidence_sha256, observed_key_sha256,
                     external_bundle_sha256, external_reference, commitment_sha256)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, proposalId, projectId, link.reviewerId(), witness,
                    request.clientRequestId(), link.proposalHash(), link.reviewHash(),
                    link.keySha(), request.externalBundleSha256(),
                    request.externalReference(), digest);
        } catch (DuplicateKeyException concurrent) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ceremony commitment was already recorded", concurrent);
        }
        return packet(link, existing(proposalId).get(0));
    }

    @Transactional(readOnly = true)
    public CeremonyPacket packet(
            UUID projectId, UUID proposalId, String bearer) {
        UUID actor = eligibleAdmin(projectId, bearer);
        ReviewLink link = reviewedProposal(projectId, proposalId);
        verifyEligible(link, actor);
        List<Existing> prior = existing(proposalId);
        if (prior.size() != 1 || !actor.equals(prior.get(0).witnessId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Independent witness commitment unavailable");
        }
        return packet(link, prior.get(0));
    }

    private CeremonyPacket packet(ReviewLink link, Existing record) {
        CommitRequest original = new CommitRequest(
                record.requestId(), record.proposalHash(), record.reviewHash(),
                record.observedKeyHash(), record.bundleHash(), record.reference());
        String expected = canonicalDigest(link, record.witnessId(), original);
        if (!link.reviewerId().equals(record.reviewerId()) ||
                !link.proposalHash().equals(record.proposalHash()) ||
                !link.reviewHash().equals(record.reviewHash()) ||
                !link.keySha().equals(record.observedKeyHash()) ||
                !expected.equals(record.commitmentHash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Offline ceremony evidence no longer matches its commitment");
        }
        return new CeremonyPacket(link.projectId(), link.proposalId(),
                link.keyId(), link.proposerId(), link.reviewerId(),
                record.witnessId(), record.requestId(),
                record.proposalHash(), record.reviewHash(),
                record.observedKeyHash(), record.bundleHash(), record.reference(),
                record.commitmentHash(), record.recordedAt(),
                "EVIDENCE_COMMITTED_PENDING_EXTERNAL_ATTESTATION_NO_TRUST",
                false, false, false, false, false);
    }

    private List<Existing> existing(UUID proposalId) {
        return jdbc.query("""
                SELECT proposal_reviewer_user_id, witness_user_id, client_request_id,
                       proposal_evidence_sha256, reviewer_evidence_sha256,
                       observed_key_sha256, external_bundle_sha256,
                       external_reference, commitment_sha256, recorded_at
                FROM typed_mapping_key_ceremony_commitments WHERE request_id = ?
                """, (rs, n) -> new Existing(
                    rs.getObject("proposal_reviewer_user_id", UUID.class),
                    rs.getObject("witness_user_id", UUID.class),
                    rs.getString("client_request_id"),
                    rs.getString("proposal_evidence_sha256"),
                    rs.getString("reviewer_evidence_sha256"),
                    rs.getString("observed_key_sha256"),
                    rs.getString("external_bundle_sha256"),
                    rs.getString("external_reference"),
                    rs.getString("commitment_sha256"),
                    rs.getTimestamp("recorded_at").toInstant()), proposalId);
    }

    private ReviewLink reviewedProposal(UUID projectId, UUID proposalId) {
        List<ReviewLink> links = jdbc.query("""
                SELECT proposal.project_id, proposal.id,
                       proposal.proposed_key_id, proposal.public_key_spki,
                       proposal.spki_sha256, proposal.requester_user_id,
                       original.username AS proposer_name,
                       original.email AS proposer_email,
                       proposal.client_request_id, proposal.purpose,
                       proposal.request_evidence_sha256,
                       review.reviewer_user_id,
                       reviewer.username AS reviewer_name,
                       reviewer.email AS reviewer_email,
                       review.disposition,
                       review.request_evidence_sha256 AS review_request_hash,
                       review.review_evidence_sha256 AS review_evidence_hash
                FROM typed_mapping_key_enrollment_requests proposal
                JOIN typed_mapping_key_enrollment_reviews review
                  ON review.request_id = proposal.id
                 AND review.project_id = proposal.project_id
                JOIN users original ON original.id = proposal.requester_user_id
                JOIN users reviewer ON reviewer.id = review.reviewer_user_id
                WHERE proposal.project_id = ? AND proposal.id = ?
                """, (rs, n) -> new ReviewLink(
                    rs.getObject("project_id", UUID.class),
                    rs.getObject("id", UUID.class),
                    rs.getString("proposed_key_id"),
                    rs.getBytes("public_key_spki"),
                    rs.getString("spki_sha256"),
                    rs.getObject("requester_user_id", UUID.class),
                    rs.getString("proposer_name"),
                    rs.getString("proposer_email"),
                    rs.getString("client_request_id"),
                    rs.getString("purpose"),
                    rs.getString("request_evidence_sha256"),
                    rs.getObject("reviewer_user_id", UUID.class),
                    rs.getString("reviewer_name"),
                    rs.getString("reviewer_email"),
                    rs.getString("disposition"),
                    rs.getString("review_request_hash"),
                    rs.getString("review_evidence_hash")),
                projectId, proposalId);
        if (links.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Reviewed offline key proposal unavailable");
        }
        return links.get(0);
    }

    private void verifyEligible(ReviewLink link, UUID witness) {
        // All three distinct web identities are required to remain current.
        // Distinct accounts are NOT proof of distinct humans or custody.
        if (!"REQUIRES_OFFLINE_KEY_CEREMONY".equals(link.disposition()) ||
                link.proposerId().equals(link.reviewerId()) ||
                link.proposerId().equals(witness) ||
                link.reviewerId().equals(witness)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Third-party ceremony witness separation failed");
        }
        Integer current = jdbc.queryForObject("""
                SELECT COUNT(*) FROM users original
                JOIN users reviewer ON reviewer.id = ?
                JOIN users witness ON witness.id = ?
                WHERE original.id = ?
                  AND original.is_active = TRUE AND original.is_verified = TRUE
                  AND reviewer.is_active = TRUE AND reviewer.is_verified = TRUE
                  AND witness.is_active = TRUE AND witness.is_verified = TRUE
                  AND LOWER(original.username) <> LOWER(reviewer.username)
                  AND LOWER(original.username) <> LOWER(witness.username)
                  AND LOWER(reviewer.username) <> LOWER(witness.username)
                  AND LOWER(original.email) <> LOWER(reviewer.email)
                  AND LOWER(original.email) <> LOWER(witness.email)
                  AND LOWER(reviewer.email) <> LOWER(witness.email)
                  AND NOT EXISTS (
                      SELECT 1 FROM app_users mobile
                      WHERE LOWER(mobile.username) IN (
                          LOWER(original.username), LOWER(reviewer.username),
                          LOWER(witness.username))
                         OR LOWER(mobile.email) IN (
                          LOWER(original.email), LOWER(reviewer.email),
                          LOWER(witness.email))
                  )
                  AND EXISTS (
                      SELECT 1 FROM project_members member JOIN roles role
                        ON role.id = member.role_id
                      WHERE member.project_id = ? AND member.user_id = original.id
                        AND member.is_active = TRUE AND role.is_active = TRUE
                        AND role.name IN ('PROJECT_MANAGER', 'PROJECT_ADMIN')
                        AND (role.project_id IS NULL OR role.project_id = ?)
                  )
                  AND EXISTS (
                      SELECT 1 FROM project_members member JOIN roles role
                        ON role.id = member.role_id
                      WHERE member.project_id = ? AND member.user_id = reviewer.id
                        AND member.is_active = TRUE AND role.is_active = TRUE
                        AND role.name = 'PROJECT_ADMIN'
                        AND (role.project_id IS NULL OR role.project_id = ?)
                  )
                """, Integer.class,
                link.reviewerId(), witness, link.proposerId(),
                link.projectId(), link.projectId(),
                link.projectId(), link.projectId());
        if (current == null || current != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Original proposer or independent review authority was revoked");
        }
        Integer keyConflicts = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM typed_mapping_trust_keys
                        WHERE key_id = ?) +
                       (SELECT COUNT(*) FROM typed_mapping_key_revocations
                        WHERE key_id = ?)
                """, Integer.class, link.keyId(), link.keyId());
        if (keyConflicts == null || keyConflicts != 0 ||
                link.spki() == null || link.spki().length != 44 ||
                !validHash(link.keySha()) ||
                !link.keySha().equals(hashBytes(link.spki())) ||
                !link.proposalHash().equals(
                    TypedMappingKeyEnrollmentReviewService.requestDigest(
                        link.projectId(), link.proposerId(), link.clientId(),
                        link.keyId(),
                        TypedMappingKeyEnrollmentReviewService.Purpose.valueOf(
                            link.purpose()), link.keySha())) ||
                !link.proposalHash().equals(link.reviewRequestHash()) ||
                !link.reviewHash().equals(
                    TypedMappingKeyEnrollmentReviewService.reviewDigest(
                        link.projectId(), link.proposalId(),
                        link.proposerId(), link.reviewerId(),
                        link.proposalHash(),
                        TypedMappingKeyEnrollmentReviewService.Disposition
                            .REQUIRES_OFFLINE_KEY_CEREMONY))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Offline ceremony chain or trust-key state changed");
        }
    }

    private UUID eligibleAdmin(UUID project, String bearer) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (bearer == null || !bearer.startsWith("Bearer ") ||
                auth == null || !auth.isAuthenticated() ||
                !(auth.getPrincipal() instanceof UserDetails principal) ||
                !principal.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Current independent web witness required");
        }
        List<UUID> ids = jdbc.query("""
                SELECT DISTINCT person.id FROM users person
                JOIN project_members member ON member.user_id = person.id
                JOIN roles role ON role.id = member.role_id
                JOIN projects project ON project.id = member.project_id
                WHERE person.username = ? AND project.id = ?
                  AND project.is_active = TRUE
                  AND person.is_active = TRUE AND person.is_verified = TRUE
                  AND member.is_active = TRUE AND role.is_active = TRUE
                  AND role.name = 'PROJECT_ADMIN'
                  AND (role.project_id IS NULL OR role.project_id = project.id)
                  AND NOT EXISTS (
                      SELECT 1 FROM app_users mobile
                      WHERE LOWER(mobile.username) = LOWER(person.username)
                         OR LOWER(mobile.email) = LOWER(person.email)
                  )
                """, (rs, n) -> rs.getObject("id", UUID.class),
                principal.getUsername(), project);
        if (ids.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Independent project ceremony witness unavailable");
        }
        return ids.get(0);
    }

    private static boolean validHash(String value) {
        return value != null && HASH.matcher(value).matches();
    }
    private static boolean validId(String value) {
        return value != null && ID.matcher(value).matches();
    }
    private static String hashBytes(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String canonicalDigest(
            ReviewLink link, UUID witness, CommitRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : List.of(
                    "DC_OFFLINE_KEY_CEREMONY_COMMITMENT_V1",
                    link.projectId().toString(), link.proposalId().toString(),
                    link.keyId(), link.proposerId().toString(),
                    link.reviewerId().toString(), witness.toString(),
                    link.proposalHash(), link.reviewHash(), link.keySha(),
                    request.clientRequestId(), request.externalBundleSha256(),
                    request.externalReference())) {
                byte[] p = field.getBytes(StandardCharsets.UTF_8);
                digest.update(new byte[]{(byte)(p.length >>> 24),
                    (byte)(p.length >>> 16), (byte)(p.length >>> 8),
                    (byte)p.length});
                digest.update(p);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot hash ceremony evidence", ex);
        }
    }
}
