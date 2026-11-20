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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M3D4B2B2A: reviewer/signer-scoped static field policy pre-authorization.
 *
 * This endpoint never reads source answers, issues signed rules, exposes a
 * transfer token or changes a submission. A positive STATIC finding is not
 * a positive grant. It must never be consumed by Android as permission.
 */
@Service
@RequiredArgsConstructor
public class TypedMappingPolicyInspectionService {
    private final JdbcTemplate jdbc;
    private final TypedMappingFinalDenialService denials;

    public record CandidateInspection(
            UUID projectId, UUID workId, UUID recommendationId,
            UUID originatingReviewerId, UUID independentSignerId,
            String recommendationEvidenceSha256,
            String sourceXFormSha256, String targetXFormSha256,
            List<StaticTypedMappingFieldPolicy.Finding> fields,
            String state, boolean canApproveMapping, boolean canReadHistoricalAnswers,
            boolean canIssueGrant, boolean canTransfer, boolean canSubmit) {
        public CandidateInspection {
            fields = List.copyOf(fields);
            if (canApproveMapping || canReadHistoricalAnswers ||
                    canIssueGrant || canTransfer || canSubmit) {
                throw new IllegalArgumentException(
                        "Static typed mapping inspection is not grant authority");
            }
        }
    }

    private record Review(
            UUID reviewerId, String requestKey,
            TypedMappingRecommendationService.ReasonCode reason,
            String storedDigest, UUID sourceSubmissionId,
            UUID targetFormVersionId, String sourceInstanceId,
            String sourceXmlSha256, String sourceXFormSha256,
            String targetXFormSha256, String historicalXForm,
            String targetXForm) { }

    @Transactional(readOnly = true)
    public CandidateInspection inspect(
            UUID projectId, UUID workId, UUID recommendationId,
            String authorization) {
        // Fresh JWT web identity, independent PROJECT_ADMIN role, original
        // reviewer project membership, READY work and source/target hashes.
        var independent = denials.eligibility(
                projectId, workId, recommendationId, authorization);

        // An independent FINAL DENIAL vetoes every subsequent positive rule
        // inspection for this recommendation, even if another signer asks.
        Integer vetoCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM typed_mapping_final_denials
                WHERE project_id = ? AND work_item_id = ? AND recommendation_id = ?
                """, Integer.class, projectId, workId, recommendationId);
        if (vetoCount == null || vetoCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Recommendation has a final denial veto");
        }

        List<Review> records = jdbc.query("""
                SELECT r.reviewer_user_id, r.client_request_id, r.reason_code,
                       r.evidence_sha256, r.source_submission_id,
                       r.target_form_version_id, r.source_instance_id,
                       r.source_xml_sha256, r.source_xform_sha256,
                       r.target_xform_sha256,
                       historic.xml_content AS historical_xform,
                       target.xml_content AS target_xform
                FROM typed_mapping_review_recommendations r
                JOIN record_work_items w
                  ON w.id = r.work_item_id AND w.project_id = r.project_id
                JOIN form_versions historic ON historic.id = w.source_form_version_id
                JOIN form_versions target ON target.id = w.target_form_version_id
                WHERE r.id = ? AND r.project_id = ? AND r.work_item_id = ?
                  AND w.state = 'READY' AND w.work_type = 'AMENDMENT'
                  AND r.source_submission_id = w.source_submission_id
                  AND r.source_instance_id = w.source_instance_id
                  AND r.target_form_version_id = w.target_form_version_id
                """, (rs, index) -> new Review(
                    rs.getObject("reviewer_user_id", UUID.class),
                    rs.getString("client_request_id"),
                    TypedMappingRecommendationService.ReasonCode.valueOf(
                        rs.getString("reason_code")),
                    rs.getString("evidence_sha256"),
                    rs.getObject("source_submission_id", UUID.class),
                    rs.getObject("target_form_version_id", UUID.class),
                    rs.getString("source_instance_id"),
                    rs.getString("source_xml_sha256"),
                    rs.getString("source_xform_sha256"),
                    rs.getString("target_xform_sha256"),
                    rs.getString("historical_xform"),
                    rs.getString("target_xform")),
                recommendationId, projectId, workId);
        if (records.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Independent mapping policy context unavailable");
        }
        Review record = records.get(0);
        if (!record.reviewerId().equals(independent.originalReviewerId()) ||
                !record.storedDigest().equals(
                        independent.recommendationEvidenceSha256()) ||
                !sha256(record.historicalXForm()).equals(record.sourceXFormSha256()) ||
                !sha256(record.targetXForm()).equals(record.targetXFormSha256())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Historical mapping evidence has changed");
        }

        Map<String, TypedMappingRecommendationService.Disposition> decisions =
                new LinkedHashMap<>();
        jdbc.query("""
                SELECT target_path, disposition
                FROM typed_mapping_review_fields
                WHERE recommendation_id = ?
                ORDER BY target_path
                """, rs -> {
            String path = rs.getString("target_path");
            TypedMappingRecommendationService.Disposition disposition =
                    TypedMappingRecommendationService.Disposition.valueOf(
                        rs.getString("disposition"));
            if (decisions.put(path, disposition) != null) {
                throw new IllegalStateException("Duplicate reviewer field path");
            }
        }, recommendationId);
        if (decisions.isEmpty() || decisions.size() > 30 ||
                record.reason() !=
                    TypedMappingRecommendationService.ReasonCode.EXPLICIT_RULE_REVIEW_NEEDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No independently reviewable escalation was recorded");
        }
        var context = new TypedMappingRecommendationService.RecommendationContext(
                projectId, workId, record.reviewerId(),
                record.sourceSubmissionId(), record.targetFormVersionId(),
                record.sourceInstanceId(), record.sourceXmlSha256(),
                record.sourceXFormSha256(), record.targetXFormSha256(),
                "REVIEW_RECOMMENDATION_ONLY", false, false, false);
        String recalculated = TypedMappingRecommendationService.digest(
                context, record.requestKey(), record.reason(), decisions);
        if (!recalculated.equals(record.storedDigest())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Stored reviewer field decisions changed");
        }

        List<String> escalated = new ArrayList<>();
        for (var entry : decisions.entrySet()) {
            if (entry.getValue() ==
                    TypedMappingRecommendationService.Disposition.ESCALATE_FOR_RULE_REVIEW) {
                escalated.add(entry.getKey());
            }
        }
        if (escalated.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No field was escalated for rule review");
        }

        var inspected = StaticTypedMappingFieldPolicy.inspect(
                record.historicalXForm(), record.targetXForm(), escalated);
        return new CandidateInspection(
                projectId, workId, recommendationId,
                independent.originalReviewerId(), independent.finalAuthorizerId(),
                record.storedDigest(), record.sourceXFormSha256(),
                record.targetXFormSha256(), inspected,
                "ADVISORY_STATIC_FIELD_REVIEW_ONLY_NO_GRANT",
                false, false, false, false, false);
    }

    private static String sha256(String content) {
        if (content == null || content.isBlank() || content.length() > 8_388_608) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Current exact XForm not available for policy review");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM SHA-256 provider missing", e);
        }
    }
}
