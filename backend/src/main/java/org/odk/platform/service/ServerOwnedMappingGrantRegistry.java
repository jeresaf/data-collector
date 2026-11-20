package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * M3D4C2A: inactive server-owned trust registry verification only.
 *
 * The database starts with ZERO trusted keys and ZERO grants. There is no
 * issuer, key registration/revocation API or caller-facing endpoint. Even
 * successful signature + registry lookup is evidence only: historical data
 * access, first-save and submission remain disabled. No client-controlled
 * default-allow revocation callback is accepted here.
 */
@Service
@RequiredArgsConstructor
public class ServerOwnedMappingGrantRegistry {
    private final JdbcTemplate jdbc;

    public record RegistryEvidence(
            UUID grantId, String state, boolean canReadHistoricalAnswers,
            boolean canTransfer, boolean canFinalize, boolean canSubmit) {
        public RegistryEvidence {
            if (canReadHistoricalAnswers || canTransfer || canFinalize || canSubmit) {
                throw new IllegalArgumentException(
                    "The inactive trust registry cannot issue operational rights");
            }
        }
    }

    private record TrustedKey(
            String keyId, UUID projectId, String algorithm,
            byte[] publicKey, Instant validFrom, Instant validUntil) { }

    private record RegisteredGrant(
            UUID grantId, UUID projectId, UUID workId, UUID recommendationId,
            String keyId, UUID sourceSubmissionId, UUID targetFormVersionId,
            UUID originalReviewerId, UUID finalSignerId, String sourceInstanceId,
            String sourceXmlHash, String sourceXFormHash, String targetXFormHash,
            String recommendationHash, String canonicalHash, String signatureHash,
            Instant issuedAt, Instant notBefore, Instant expiresAt,
            String liveSourceXml, String liveSourceForm, String liveTargetForm,
            String recommendationRequest, String recommendationReason,
            String recommendationStoredHash) { }

    /**
     * This check is intentionally not part of any HTTP, ODK, or upload route.
     * Any database absence, drift, revocation, undecodable key or exception
     * fails closed. A genuine operational use-stage check would ALSO require
     * fresh owner/actor/consent and serializable transactional enforcement.
     */
    @Transactional(readOnly = true)
    public RegistryEvidence inspect(
            SignedMappingGrantVerifier.SignedGrant envelope, Instant now) {
        UUID id = envelope == null ? null : envelope.grant().grantId();
        if (envelope == null || now == null) return blocked(id, "BLOCKED_MISSING_TRUST_CONTEXT");
        var g = envelope.grant();
        try {
            List<TrustedKey> keys = jdbc.query("""
                SELECT key_id, project_id, algorithm, public_key_spki,
                       valid_from, valid_until
                FROM typed_mapping_trust_keys
                WHERE key_id = ? AND project_id = ?
                """, (rs, i) -> new TrustedKey(
                    rs.getString("key_id"),
                    rs.getObject("project_id", UUID.class),
                    rs.getString("algorithm"),
                    rs.getBytes("public_key_spki"),
                    rs.getTimestamp("valid_from").toInstant(),
                    rs.getTimestamp("valid_until").toInstant()),
                g.signingKeyId(), g.projectId());
            if (keys.size() != 1) return blocked(id, "BLOCKED_UNREGISTERED_TRUST_KEY");
            TrustedKey trusted = keys.get(0);
            if (!"Ed25519".equals(trusted.algorithm()) ||
                    trusted.publicKey() == null || trusted.publicKey().length > 128 ||
                    trusted.publicKey().length < 32 ||
                    now.isBefore(trusted.validFrom()) ||
                    !now.isBefore(trusted.validUntil()) ||
                    g.issuedAt().isBefore(trusted.validFrom()) ||
                    !g.issuedAt().isBefore(trusted.validUntil())) {
                return blocked(id, "BLOCKED_KEY_NOT_TRUSTED_AT_TIME");
            }

            List<RegisteredGrant> records = jdbc.query("""
                SELECT registered.grant_id, registered.project_id, registered.work_item_id,
                       registered.recommendation_id, registered.signing_key_id,
                       registered.source_submission_id, registered.target_form_version_id,
                       registered.original_reviewer_id, registered.final_signer_id,
                       registered.source_instance_id, registered.source_xml_sha256,
                       registered.source_xform_sha256, registered.target_xform_sha256,
                       registered.recommendation_evidence_sha256,
                       registered.canonical_envelope_sha256, registered.signature_sha256,
                       registered.issued_at, registered.not_before, registered.expires_at,
                       src.xml_content AS live_source_xml,
                       historical.xml_content AS live_source_form,
                       target.xml_content AS live_target_form,
                       recommendation.client_request_id AS recommendation_request,
                       recommendation.reason_code AS recommendation_reason,
                       recommendation.evidence_sha256 AS recommendation_stored_hash
                FROM typed_mapping_signed_grant_registry registered
                JOIN record_work_items work
                  ON work.id = registered.work_item_id
                 AND work.project_id = registered.project_id
                JOIN submissions src ON src.id = registered.source_submission_id
                JOIN form_versions historical
                  ON historical.id = work.source_form_version_id
                JOIN form_versions target
                  ON target.id = work.target_form_version_id
                JOIN xforms xform ON xform.id = src.form_id
                JOIN typed_mapping_review_recommendations recommendation
                  ON recommendation.id = registered.recommendation_id
                 AND recommendation.work_item_id = work.id
                 AND recommendation.project_id = work.project_id
                JOIN users reviewer ON reviewer.id = registered.original_reviewer_id
                JOIN users signer ON signer.id = registered.final_signer_id
                WHERE registered.grant_id = ? AND registered.project_id = ?
                  AND registered.signing_key_id = ?
                  AND work.state = 'READY' AND work.work_type = 'AMENDMENT'
                  AND work.source_submission_id = registered.source_submission_id
                  AND work.source_instance_id = registered.source_instance_id
                  AND src.instance_id = work.source_instance_id
                  AND src.form_version_id = historical.id
                  AND historical.form_id = src.form_id
                  AND target.form_id = src.form_id
                  AND xform.project_id = work.project_id
                  AND xform.is_active = TRUE AND xform.is_published = TRUE
                  AND target.is_published = TRUE
                  AND recommendation.source_submission_id = src.id
                  AND recommendation.target_form_version_id = target.id
                  AND recommendation.reviewer_user_id = registered.original_reviewer_id
                  AND reviewer.is_active = TRUE AND reviewer.is_verified = TRUE
                  AND signer.is_active = TRUE AND signer.is_verified = TRUE
                  AND reviewer.username <> signer.username
                  AND LOWER(reviewer.email) <> LOWER(signer.email)
                  AND NOT EXISTS (
                      SELECT 1 FROM app_users mobile
                      WHERE LOWER(mobile.username) IN
                          (LOWER(reviewer.username), LOWER(signer.username))
                         OR LOWER(mobile.email) IN
                          (LOWER(reviewer.email), LOWER(signer.email))
                  )
                  AND EXISTS (
                      SELECT 1 FROM project_members pm
                      JOIN roles role ON role.id = pm.role_id
                      WHERE pm.project_id = registered.project_id
                        AND pm.user_id = reviewer.id
                        AND pm.is_active = TRUE AND role.is_active = TRUE
                        AND role.name IN ('PROJECT_MANAGER', 'PROJECT_ADMIN')
                        AND (role.project_id IS NULL OR role.project_id = pm.project_id)
                  )
                  AND EXISTS (
                      SELECT 1 FROM project_members pm
                      JOIN roles role ON role.id = pm.role_id
                      WHERE pm.project_id = registered.project_id
                        AND pm.user_id = signer.id
                        AND pm.is_active = TRUE AND role.is_active = TRUE
                        AND role.name = 'PROJECT_ADMIN'
                        AND (role.project_id IS NULL OR role.project_id = pm.project_id)
                  )
                """, (rs, i) -> new RegisteredGrant(
                    rs.getObject("grant_id", UUID.class),
                    rs.getObject("project_id", UUID.class),
                    rs.getObject("work_item_id", UUID.class),
                    rs.getObject("recommendation_id", UUID.class),
                    rs.getString("signing_key_id"),
                    rs.getObject("source_submission_id", UUID.class),
                    rs.getObject("target_form_version_id", UUID.class),
                    rs.getObject("original_reviewer_id", UUID.class),
                    rs.getObject("final_signer_id", UUID.class),
                    rs.getString("source_instance_id"),
                    rs.getString("source_xml_sha256"),
                    rs.getString("source_xform_sha256"),
                    rs.getString("target_xform_sha256"),
                    rs.getString("recommendation_evidence_sha256"),
                    rs.getString("canonical_envelope_sha256"),
                    rs.getString("signature_sha256"),
                    rs.getTimestamp("issued_at").toInstant(),
                    rs.getTimestamp("not_before").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getString("live_source_xml"),
                    rs.getString("live_source_form"),
                    rs.getString("live_target_form"),
                    rs.getString("recommendation_request"),
                    rs.getString("recommendation_reason"),
                    rs.getString("recommendation_stored_hash")),
                g.grantId(), g.projectId(), g.signingKeyId());
            if (records.size() != 1) return blocked(id, "BLOCKED_GRANT_NOT_REGISTERED_OR_WORK_CHANGED");
            RegisteredGrant row = records.get(0);

            if (!g.workId().equals(row.workId()) ||
                    !g.recommendationId().equals(row.recommendationId()) ||
                    !g.sourceSubmissionId().equals(row.sourceSubmissionId()) ||
                    !g.targetFormVersionId().equals(row.targetFormVersionId()) ||
                    !g.originalReviewerId().equals(row.originalReviewerId()) ||
                    !g.finalSignerId().equals(row.finalSignerId()) ||
                    !g.sourceInstanceId().equals(row.sourceInstanceId()) ||
                    !g.issuedAt().equals(row.issuedAt()) ||
                    !g.notBefore().equals(row.notBefore()) ||
                    !g.expiresAt().equals(row.expiresAt()) ||
                    !g.sourceXmlSha256().equals(row.sourceXmlHash()) ||
                    !g.sourceXFormSha256().equals(row.sourceXFormHash()) ||
                    !g.targetXFormSha256().equals(row.targetXFormHash()) ||
                    !g.recommendationEvidenceSha256().equals(row.recommendationHash()) ||
                    !sha256(row.liveSourceXml()).equals(g.sourceXmlSha256()) ||
                    !sha256(row.liveSourceForm()).equals(g.sourceXFormSha256()) ||
                    !sha256(row.liveTargetForm()).equals(g.targetXFormSha256()) ||
                    !sha256(SignedMappingGrantVerifier.canonicalBytes(g))
                        .equals(row.canonicalHash()) ||
                    !sha256(Base64.getUrlDecoder().decode(envelope.signatureBase64Url()))
                        .equals(row.signatureHash()) ||
                    !g.recommendationEvidenceSha256().equals(row.recommendationStoredHash())) {
                return blocked(id, "BLOCKED_LIVE_SOURCE_OR_REGISTRY_DRIFT");
            }

            List<SignedMappingGrantVerifier.FieldRule> rules = jdbc.query("""
                SELECT field_path, native_type
                FROM typed_mapping_registered_grant_fields
                WHERE grant_id = ? ORDER BY field_path
                """, (rs, i) -> new SignedMappingGrantVerifier.FieldRule(
                    rs.getString("field_path"),
                    SignedMappingGrantVerifier.NativeType.valueOf(
                        rs.getString("native_type"))), id);
            if (!rules.equals(g.fieldRules())) {
                return blocked(id, "BLOCKED_FIELD_SCOPE_DRIFT");
            }

            // The original evidence is still the current exact recommendation
            // digest, not just an ID. Prevent privileged SQL field edits from
            // silently becoming valid merely because the signed grant exists.
            var dispositions = new java.util.TreeMap<String,
                TypedMappingRecommendationService.Disposition>();
            jdbc.query("""
                SELECT target_path, disposition FROM typed_mapping_review_fields
                WHERE recommendation_id = ? ORDER BY target_path
                """, rs -> {
                var name = rs.getString("target_path");
                var decision = TypedMappingRecommendationService.Disposition.valueOf(
                    rs.getString("disposition"));
                if (dispositions.put(name, decision) != null)
                    throw new IllegalStateException("Duplicate review field");
            }, g.recommendationId());
            if (dispositions.isEmpty() ||
                    dispositions.size() > 30 ||
                    dispositions.values().stream().noneMatch(d ->
                        d == TypedMappingRecommendationService.Disposition.ESCALATE_FOR_RULE_REVIEW) ||
                    rules.stream().anyMatch(rule ->
                        dispositions.get(rule.path()) !=
                            TypedMappingRecommendationService.Disposition.ESCALATE_FOR_RULE_REVIEW)) {
                return blocked(id, "BLOCKED_RECOMMENDATION_FIELD_DRIFT");
            }
            var context = new TypedMappingRecommendationService.RecommendationContext(
                g.projectId(), g.workId(), g.originalReviewerId(),
                g.sourceSubmissionId(), g.targetFormVersionId(),
                g.sourceInstanceId(), g.sourceXmlSha256(),
                g.sourceXFormSha256(), g.targetXFormSha256(),
                "REVIEW_RECOMMENDATION_ONLY", false, false, false);
            var recomputed = TypedMappingRecommendationService.digest(
                context, row.recommendationRequest(),
                TypedMappingRecommendationService.ReasonCode.valueOf(
                    row.recommendationReason()), dispositions);
            if (!recomputed.equals(g.recommendationEvidenceSha256())) {
                return blocked(id, "BLOCKED_RECOMMENDATION_EVIDENCE_DRIFT");
            }

            // A trusted key never comes from the envelope. Only exact
            // database-sourced SPKI bytes and the database registry can
            // participate in Ed25519 verification.
            PublicKey key = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(trusted.publicKey()));
            var expected = new SignedMappingGrantVerifier.ExpectedContext(
                row.projectId(), row.workId(), row.recommendationId(),
                row.sourceSubmissionId(), row.targetFormVersionId(),
                row.originalReviewerId(), row.finalSignerId(),
                row.sourceInstanceId(), row.sourceXmlHash(),
                row.sourceXFormHash(), row.targetXFormHash(),
                row.recommendationHash(), trusted.keyId(), rules);

            var cryptographic = SignedMappingGrantVerifier.inspect(
                envelope, key, expected, now, (grantId, keyId) -> {
                    // This callback is server-owned, NOT an external/client
                    // implementation. A missing table/DB causes an exception
                    // and the verifier blocks with revocation unavailable.
                    Integer veto = jdbc.queryForObject("""
                        SELECT
                          (SELECT COUNT(*) FROM typed_mapping_grant_revocations
                           WHERE grant_id = ?) +
                          (SELECT COUNT(*) FROM typed_mapping_key_revocations
                           WHERE key_id = ?) +
                          (SELECT COUNT(*) FROM typed_mapping_final_denials
                           WHERE recommendation_id = ?)
                        """, Integer.class, grantId, keyId, g.recommendationId());
                    return veto == null || veto > 0;
                });
            return new RegistryEvidence(id, cryptographic.state(),
                false, false, false, false);
        } catch (Exception failure) {
            // No SQL error details, answer values or schema information leave
            // the server-owned inspection path, including in test fixtures.
            return blocked(id, "BLOCKED_TRUST_REGISTRY_UNAVAILABLE_OR_INVALID");
        }
    }

    private static RegistryEvidence blocked(UUID id, String reason) {
        return new RegistryEvidence(id, reason, false, false, false, false);
    }

    private static String sha256(String text) {
        if (text == null || text.isBlank() || text.length() > 8_388_608) {
            throw new IllegalArgumentException("Oversize or missing historical document");
        }
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 provider unavailable", failure);
        }
    }
}
