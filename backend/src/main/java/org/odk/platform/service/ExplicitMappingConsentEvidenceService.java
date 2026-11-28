package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Non-authorizing record of a web administrator's external consent evidence
 * attestation. The application cannot establish that an external document is
 * authentic, informed, or legally sufficient. No document or answers are read.
 * No API in this class issues a grant; no client can use its receipt to import.
 */
@Service
@RequiredArgsConstructor
public class ExplicitMappingConsentEvidenceService {
    private final JdbcTemplate jdbc;
    private final NativeTypedMappingInspectionService nativeInspection;
    private final TypedMappingFinalDenialService denials;

    public enum WithdrawalReason { CONSENT_WITHDRAWN, EVIDENCE_INVALID, SECURITY }

    public record Scope(ExplicitMappingManifest manifest, String endorsementSha256) { }
    public record Attestation(Scope scope, String externalEvidenceSha256, long expiresEpochSecond) { }
    public record Withdrawal(String consentEvidenceSha256, WithdrawalReason reason) { }

    public record Evidence(UUID recommendationId, String state, String evidenceSha256,
            Long expiresEpochSecond, boolean canReadHistoricalAnswers, boolean canIssueGrant,
            boolean canTransfer, boolean canSubmit) {
        public Evidence {
            if (canReadHistoricalAnswers || canIssueGrant || canTransfer || canSubmit)
                throw new IllegalArgumentException("External evidence is not operational authority");
        }
    }

    private record Stored(String endorsement, String manifest, String external, UUID attestor,
                          long expires, String digest) { }
    private record Endorsement(UUID reviewer, UUID signer, String recommendation, String manifest, String digest) { }

    @Transactional
    public Evidence attest(UUID project, UUID work, UUID recommendation, String auth, Attestation request) {
        denials.eligibility(project, work, recommendation, auth);
        TypedMappingRecommendationService.lockReadyWork(jdbc, project, work);
        if (request == null || !hash(request.externalEvidenceSha256()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "External consent evidence digest required");
        Endorsement endorsed = requireEndorsement(project, work, recommendation, auth, request.scope());
        long now = Instant.now().getEpochSecond();
        if (request.expiresEpochSecond() <= now || request.expiresEpochSecond() > now + 86400)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Evidence expiry must be within 24 hours");
        String digest = digest(project, work, recommendation, endorsed.signer(), endorsed.digest(),
                endorsed.manifest(), request.externalEvidenceSha256(), request.expiresEpochSecond());
        Stored expected = new Stored(endorsed.digest(), endorsed.manifest(), request.externalEvidenceSha256(),
                endorsed.signer(), request.expiresEpochSecond(), digest);
        Stored existing = stored(recommendation);
        if (withdrawn(recommendation))
            throw conflict("Consent evidence has been withdrawn");
        if (existing == null) {
            jdbc.update("""
                    INSERT INTO typed_mapping_consent_evidence
                    (recommendation_id, endorsement_sha256, manifest_sha256, external_evidence_sha256,
                     attestor_id, expires_epoch_second, evidence_sha256)
                    VALUES (?,?,?,?,?,?,?)
                    """, recommendation, expected.endorsement(), expected.manifest(), expected.external(),
                    expected.attestor(), expected.expires(), expected.digest());
        } else if (!existing.equals(expected)) {
            throw conflict("Consent evidence cannot be replaced or extended");
        }
        return evidence(recommendation, "RECORDED_EXTERNAL_CONSENT_EVIDENCE_NO_GRANT", expected);
    }

    @Transactional(readOnly = true)
    public Evidence inspect(UUID project, UUID work, UUID recommendation, String auth, Scope scope) {
        Endorsement endorsed = requireEndorsement(project, work, recommendation, auth, scope);
        Stored existing = stored(recommendation);
        if (existing == null) return evidence(recommendation, "MISSING_EXTERNAL_CONSENT_EVIDENCE", null);
        if (!existing.endorsement().equals(endorsed.digest()) ||
                !existing.manifest().equals(endorsed.manifest()) ||
                !existing.attestor().equals(endorsed.signer()) ||
                !hash(existing.external()) ||
                !existing.digest().equals(digest(project, work, recommendation, existing.attestor(),
                        existing.endorsement(), existing.manifest(), existing.external(), existing.expires())))
            throw conflict("Consent evidence integrity differs");
        String state = withdrawn(recommendation) ? "WITHDRAWN_NO_GRANT" :
                existing.expires() <= Instant.now().getEpochSecond() ? "EXPIRED_NO_GRANT" :
                "EXTERNAL_CONSENT_ATTESTATION_ONLY_NO_GRANT";
        return evidence(recommendation, state, existing);
    }

    @Transactional
    public Evidence withdraw(UUID project, UUID work, UUID recommendation, String auth, Withdrawal request) {
        // Denial eligibility intentionally does not require an unvetoed policy:
        // a current independent administrator may record withdrawal after a denial.
        denials.eligibility(project, work, recommendation, auth);
        TypedMappingRecommendationService.lockReadyWork(jdbc, project, work);
        var current = denials.eligibility(project, work, recommendation, auth);
        Stored existing = stored(recommendation);
        if (request == null || request.reason() == null || !hash(request.consentEvidenceSha256()) ||
                existing == null || !existing.digest().equals(request.consentEvidenceSha256()))
            throw conflict("Exact existing consent evidence required");
        var rows = jdbc.query("""
                SELECT consent_evidence_sha256, recorded_by, reason
                FROM typed_mapping_consent_withdrawals WHERE recommendation_id=?
                """, (rs, n) -> List.of(rs.getString(1), rs.getObject(2, UUID.class).toString(),
                        rs.getString(3)), recommendation);
        var expected = List.of(existing.digest(), current.finalAuthorizerId().toString(), request.reason().name());
        if (rows.isEmpty()) {
            jdbc.update("""
                    INSERT INTO typed_mapping_consent_withdrawals
                    (recommendation_id, consent_evidence_sha256, recorded_by, reason) VALUES (?,?,?,?)
                    """, recommendation, existing.digest(), current.finalAuthorizerId(), request.reason().name());
        } else if (rows.size() != 1 || !rows.get(0).equals(expected)) {
            throw conflict("Withdrawal already recorded");
        }
        return evidence(recommendation, "WITHDRAWN_NO_GRANT", existing);
    }

    private Endorsement requireEndorsement(UUID project, UUID work, UUID recommendation, String auth, Scope scope) {
        if (scope == null || scope.manifest() == null || !hash(scope.endorsementSha256()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Exact endorsement and manifest required");
        var proof = nativeInspection.inspectExplicit(project, work, recommendation, auth, scope.manifest());
        if (!"REVIEWER_BOUND_NATIVE_SCHEMA_ONLY_NO_GRANT".equals(proof.state()) || proof.fields().isEmpty() ||
                proof.fields().stream().anyMatch(f -> !"NATIVE_EXPLICIT_CANDIDATE_ONLY".equals(f.state())))
            throw conflict("Current reviewer-bound native proof required");
        var rows = jdbc.query("""
                SELECT reviewer_id, signer_id, recommendation_sha256, manifest_sha256, evidence_sha256
                FROM typed_mapping_explicit_endorsements WHERE recommendation_id=?
                """, (rs, n) -> new Endorsement(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5)), recommendation);
        if (rows.size() != 1) throw conflict("Recorded compatibility endorsement required");
        var row = rows.get(0);
        String recomputed = sha(String.join("\n", "DC_EXPLICIT_ENDORSEMENT_V1",
                project.toString(), work.toString(), recommendation.toString(),
                proof.originatingReviewerId().toString(), proof.independentSignerId().toString(),
                proof.recommendationEvidenceSha256(), proof.manifestSha256()));
        if (!row.reviewer().equals(proof.originatingReviewerId()) ||
                !row.signer().equals(proof.independentSignerId()) ||
                !row.recommendation().equals(proof.recommendationEvidenceSha256()) ||
                !row.manifest().equals(proof.manifestSha256()) ||
                !row.digest().equals(recomputed) || !row.digest().equals(scope.endorsementSha256()))
            throw conflict("Current signer or endorsement differs");
        return row;
    }

    private Stored stored(UUID recommendation) {
        var rows = jdbc.query("""
                SELECT endorsement_sha256, manifest_sha256, external_evidence_sha256,
                       attestor_id, expires_epoch_second, evidence_sha256
                FROM typed_mapping_consent_evidence WHERE recommendation_id=?
                """, (rs, n) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getLong(5), rs.getString(6)), recommendation);
        if (rows.size() > 1) throw conflict("Ambiguous consent evidence");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean withdrawn(UUID recommendation) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM typed_mapping_consent_withdrawals WHERE recommendation_id=?",
                Integer.class, recommendation);
        return count == null || count != 0;
    }

    static String digest(UUID project, UUID work, UUID recommendation, UUID attestor,
                         String endorsement, String manifest, String external, long expiry) {
        return sha(String.join("\n", "DC_EXTERNAL_CONSENT_EVIDENCE_V1", project.toString(),
                work.toString(), recommendation.toString(), attestor.toString(),
                endorsement, manifest, external, Long.toString(expiry)));
    }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
    private static Evidence evidence(UUID recommendation, String state, Stored stored) {
        return new Evidence(recommendation, state, stored == null ? null : stored.digest(),
                stored == null ? null : stored.expires(), false, false, false, false);
    }
}
