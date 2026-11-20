package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M3D4B2B1 independent FINAL DENIAL only; no mapping APPROVAL, token or grant.
 * A project admin can deny another reviewer's recommendation after fresh
 * JWT identity, project membership, work and exact-XForm checks.
 */
@Service
@RequiredArgsConstructor
public class TypedMappingFinalDenialService {
    private final JdbcTemplate jdbc;
    private final TypedMappingReviewerEligibilityService reviewerEligibility;
    private final TypedMappingRecommendationService recommendationService;

    public enum Reason {
        INSUFFICIENT_EVIDENCE, SCHEMA_UNSAFE, CONSENT_UNCLEAR,
        SOURCE_UNTRUSTED, POLICY_NOT_SATISFIED
    }

    public record DenialRequest(
            String clientRequestId, Reason reasonCode,
            String recommendationEvidenceSha256) { }

    public record DenialEligibility(
            UUID projectId, UUID workId, UUID recommendationId,
            UUID originalReviewerId, UUID finalAuthorizerId,
            String recommendationEvidenceSha256, String state,
            boolean canApproveMapping, boolean canTransfer, boolean canSubmit
    ) {
        public DenialEligibility {
            if (canApproveMapping || canTransfer || canSubmit) {
                throw new IllegalArgumentException(
                    "Final-authorizer eligibility cannot issue a mapping grant");
            }
        }
    }

    public record DenialReceipt(
            UUID denialId, UUID recommendationId, UUID workId,
            UUID finalAuthorizerId, String denialEvidenceSha256, String state,
            boolean canApproveMapping, boolean canReadHistoricalAnswers,
            boolean canTransfer, boolean canSubmit
    ) {
        public DenialReceipt {
            if (canApproveMapping || canReadHistoricalAnswers ||
                    canTransfer || canSubmit) {
                throw new IllegalArgumentException(
                    "A final denial cannot authorize historical answer transfer");
            }
        }
    }

    private record Recommendation(
            UUID reviewerId, String reviewerName, String reviewerEmail,
            String signerName, String signerEmail, String evidenceSha256,
            UUID sourceSubmissionId, UUID targetFormVersionId,
            String sourceInstanceId, String sourceXmlSha256,
            String sourceXFormSha256, String targetXFormSha256) { }

    private record Existing(UUID id, UUID authorizerId, String requestId,
                            Reason reason, String recommendationSha256,
                            String denialSha256) { }

    private static final Pattern IDEMPOTENCY =
            Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    @Transactional(readOnly = true)
    public DenialEligibility eligibility(
            UUID projectId, UUID workId, UUID recommendationId,
            String authorization) {
        var reviewer = reviewerEligibility.inspect(projectId, workId, authorization);
        UUID signer = reviewer.reviewerId();

        // Signer requires CURRENT project-admin membership, not just JWT role
        // claims. A global administrator has no implicit project bypass.
        Integer projectAdmin = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project_members pm
                JOIN roles role ON role.id = pm.role_id
                JOIN users u ON u.id = pm.user_id
                JOIN projects p ON p.id = pm.project_id
                WHERE pm.project_id = ? AND pm.user_id = ?
                  AND pm.is_active = TRUE AND role.is_active = TRUE
                  AND role.name = 'PROJECT_ADMIN'
                  AND (role.project_id IS NULL OR role.project_id = pm.project_id)
                  AND u.is_active = TRUE AND u.is_verified = TRUE
                  AND p.is_active = TRUE
                """, Integer.class, projectId, signer);
        if (projectAdmin == null || projectAdmin < 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Independent final authorizer not eligible");
        }

        var live = recommendationService.context(projectId, workId, authorization);
        List<Recommendation> matches = jdbc.query("""
                SELECT r.reviewer_user_id, original.username AS reviewer_name,
                       original.email AS reviewer_email,
                       signer.username AS signer_name, signer.email AS signer_email,
                       r.evidence_sha256, r.source_submission_id,
                       r.target_form_version_id, r.source_instance_id,
                       r.source_xml_sha256, r.source_xform_sha256,
                       r.target_xform_sha256
                FROM typed_mapping_review_recommendations r
                JOIN users original ON original.id = r.reviewer_user_id
                JOIN users signer ON signer.id = ?
                WHERE r.id = ? AND r.project_id = ? AND r.work_item_id = ?
                  AND original.is_active = TRUE AND original.is_verified = TRUE
                  AND EXISTS (
                      SELECT 1 FROM project_members pm
                      JOIN roles rr ON rr.id = pm.role_id
                      WHERE pm.project_id = r.project_id
                        AND pm.user_id = r.reviewer_user_id
                        AND pm.is_active = TRUE AND rr.is_active = TRUE
                        AND rr.name IN ('PROJECT_MANAGER', 'PROJECT_ADMIN')
                        AND (rr.project_id IS NULL OR rr.project_id = pm.project_id)
                  )
                  AND EXISTS (
                      SELECT 1 FROM typed_mapping_review_fields field
                      WHERE field.recommendation_id = r.id
                  )
                """, (rs, idx) -> new Recommendation(
                    rs.getObject("reviewer_user_id", UUID.class),
                    rs.getString("reviewer_name"),
                    rs.getString("reviewer_email"),
                    rs.getString("signer_name"),
                    rs.getString("signer_email"),
                    rs.getString("evidence_sha256"),
                    rs.getObject("source_submission_id", UUID.class),
                    rs.getObject("target_form_version_id", UUID.class),
                    rs.getString("source_instance_id"),
                    rs.getString("source_xml_sha256"),
                    rs.getString("source_xform_sha256"),
                    rs.getString("target_xform_sha256")),
                signer, recommendationId, projectId, workId);
        if (matches.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Reviewer recommendation is not eligible");
        }
        Recommendation r = matches.get(0);

        // Distinct accounts alone are insufficient: block obvious identity
        // overlap and require separate assigned web reviewers. Strong human
        // identity proof still belongs to the later final issuer milestone.
        if (r.reviewerId().equals(signer) ||
                r.reviewerName().equalsIgnoreCase(r.signerName()) ||
                r.reviewerEmail().equalsIgnoreCase(r.signerEmail()) ||
                !SHA256.matcher(r.evidenceSha256()).matches() ||
                !r.sourceSubmissionId().equals(live.sourceSubmissionId()) ||
                !r.targetFormVersionId().equals(live.targetFormVersionId()) ||
                !r.sourceInstanceId().equals(live.sourceInstanceId()) ||
                !r.sourceXmlSha256().equals(live.sourceXmlSha256()) ||
                !r.sourceXFormSha256().equals(live.sourceXFormSha256()) ||
                !r.targetXFormSha256().equals(live.targetXFormSha256())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Review is not independent or exact record/schema changed");
        }
        return new DenialEligibility(projectId, workId, recommendationId,
                r.reviewerId(), signer, r.evidenceSha256(),
                "ELIGIBLE_TO_DENY_ONLY", false, false, false);
    }

    @Transactional
    public DenialReceipt deny(
            UUID projectId, UUID workId, UUID recommendationId,
            String authorization, DenialRequest request) {
        if (request == null || request.clientRequestId() == null ||
                !IDEMPOTENCY.matcher(request.clientRequestId()).matches() ||
                request.reasonCode() == null ||
                request.recommendationEvidenceSha256() == null ||
                !SHA256.matcher(request.recommendationEvidenceSha256()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bounded final denial and recommendation fingerprint required");
        }
        List<UUID> locks = jdbc.query("""
                SELECT id FROM record_work_items
                WHERE project_id = ? AND id = ? AND state = 'READY'
                FOR UPDATE
                """, (rs, idx) -> rs.getObject("id", UUID.class),
                projectId, workId);
        if (locks.size() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Work is no longer eligible for final denial");
        }
        DenialEligibility ctx = eligibility(
                projectId, workId, recommendationId, authorization);
        if (!ctx.recommendationEvidenceSha256()
                .equals(request.recommendationEvidenceSha256())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Reviewer evidence has changed");
        }

        String digest = digest(ctx, request);
        List<Existing> existing = jdbc.query("""
                SELECT id, final_authorizer_user_id, client_request_id,
                       reason_code, recommendation_evidence_sha256,
                       denial_evidence_sha256
                FROM typed_mapping_final_denials
                WHERE recommendation_id = ?
                """, (rs, idx) -> new Existing(
                rs.getObject("id", UUID.class),
                rs.getObject("final_authorizer_user_id", UUID.class),
                rs.getString("client_request_id"),
                Reason.valueOf(rs.getString("reason_code")),
                rs.getString("recommendation_evidence_sha256"),
                rs.getString("denial_evidence_sha256")), recommendationId);
        if (!existing.isEmpty()) {
            Existing prior = existing.get(0);
            if (!prior.authorizerId().equals(ctx.finalAuthorizerId()) ||
                    !prior.requestId().equals(request.clientRequestId()) ||
                    prior.reason() != request.reasonCode() ||
                    !prior.recommendationSha256()
                        .equals(request.recommendationEvidenceSha256()) ||
                    !prior.denialSha256().equals(digest)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Final denial already recorded with different intent");
            }
            return receipt(prior.id(), ctx, digest);
        }

        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO typed_mapping_final_denials
                    (id, project_id, work_item_id, recommendation_id,
                     original_reviewer_user_id, final_authorizer_user_id,
                     client_request_id, reason_code, recommendation_evidence_sha256,
                     denial_evidence_sha256)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, projectId, workId, recommendationId,
                    ctx.originalReviewerId(), ctx.finalAuthorizerId(),
                    request.clientRequestId(), request.reasonCode().name(),
                    request.recommendationEvidenceSha256(), digest);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Concurrent final denial already recorded", exception);
        }
        return receipt(id, ctx, digest);
    }

    private static DenialReceipt receipt(
            UUID id, DenialEligibility ctx, String digest) {
        return new DenialReceipt(id, ctx.recommendationId(),
                ctx.workId(), ctx.finalAuthorizerId(), digest,
                "DENIED_NO_MAPPING_GRANT", false, false, false, false);
    }

    private static String digest(DenialEligibility ctx, DenialRequest request) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String value : List.of("DC_FINAL_TYPED_DENIAL_V1",
                    ctx.projectId().toString(), ctx.workId().toString(),
                    ctx.recommendationId().toString(),
                    ctx.originalReviewerId().toString(),
                    ctx.finalAuthorizerId().toString(),
                    ctx.recommendationEvidenceSha256(),
                    request.clientRequestId(), request.reasonCode().name())) {
                byte[] data = value.getBytes(StandardCharsets.UTF_8);
                md.update(ByteBuffer.allocate(4).putInt(data.length).array());
                md.update(data);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM SHA-256 unavailable", exception);
        }
    }
}
