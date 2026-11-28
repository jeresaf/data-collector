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
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * M3D4B2B2B: read-only JavaRosa cross-qualification for a current,
 * independent web Project Admin. It reuses the original-signer separation,
 * recommendation-evidence rehashing and final-denial veto before touching
 * archived form definitions. Never loads source submission answers.
 */
@Service
@RequiredArgsConstructor
public class NativeTypedMappingInspectionService {
    private final TypedMappingPolicyInspectionService staticPolicy;
    private final JdbcTemplate jdbc;
    private final JavaRosaFormLoader loader;

    public record NativeInspection(
            UUID projectId, UUID workId, UUID recommendationId,
            UUID originatingReviewerId, UUID independentSignerId,
            String sourceXFormSha256, String targetXFormSha256,
            List<NativeTypedMappingSchemaProbe.Evidence> fields,
            String state, boolean canApproveMapping, boolean canReadHistoricalAnswers,
            boolean canIssueGrant, boolean canTransfer,
            boolean canFinalize, boolean canSubmit) {
        public NativeInspection {
            fields = List.copyOf(fields);
            if (canApproveMapping || canReadHistoricalAnswers || canIssueGrant ||
                    canTransfer || canFinalize || canSubmit) {
                throw new IllegalArgumentException(
                    "Native type compatibility does not authorize a transfer");
            }
        }
    }

    private record ExactForms(String sourceXForm, String targetXForm) { }

    @Transactional(readOnly = true)
    public NativeInspection inspect(
            UUID projectId, UUID workId, UUID recommendationId,
            String authorization) {
        var permitted = staticPolicy.inspect(
                projectId, workId, recommendationId, authorization);

        ExactForms forms = exactForms(projectId, workId, recommendationId, permitted);

        List<String> paths = permitted.fields().stream()
                .map(StaticTypedMappingFieldPolicy.Finding::targetPath).toList();
        var proof = NativeTypedMappingSchemaProbe.inspect(
                forms.sourceXForm(), forms.targetXForm(), paths, loader);
        return new NativeInspection(
                projectId, workId, recommendationId,
                permitted.originatingReviewerId(),
                permitted.independentSignerId(),
                permitted.sourceXFormSha256(), permitted.targetXFormSha256(),
                proof, "ADVISORY_NATIVE_SCHEMA_PROOF_ONLY_NO_GRANT",
                false, false, false, false, false, false);
    }

    public record ExplicitInspection(UUID projectId, UUID workId, UUID recommendationId,
            UUID originatingReviewerId, UUID independentSignerId,
            String recommendationEvidenceSha256, String manifestSha256,
            List<NativeTypedMappingSchemaProbe.ExplicitEvidence> fields, String state,
            boolean canApproveMapping, boolean canReadHistoricalAnswers,
            boolean canIssueGrant, boolean canTransfer, boolean canFinalize, boolean canSubmit) {
        public ExplicitInspection {
            fields = List.copyOf(fields);
            if (canApproveMapping || canReadHistoricalAnswers || canIssueGrant ||
                    canTransfer || canFinalize || canSubmit)
                throw new IllegalArgumentException("Explicit inspection cannot grant rights");
        }
    }

    @Transactional(readOnly = true)
    public ExplicitInspection inspectExplicit(UUID projectId, UUID workId, UUID recommendationId,
            String authorization, ExplicitMappingManifest manifest) {
        // Reuse fresh independent reviewer/signer policy, evidence rehash and denial veto.
        var permitted = staticPolicy.inspect(projectId, workId, recommendationId, authorization);
        ExactForms forms = exactForms(projectId, workId, recommendationId, permitted);
        UUID sourceId = jdbc.queryForObject(
                "SELECT source_submission_id FROM record_work_items WHERE id=? AND project_id=?",
                UUID.class, workId, projectId);
        UUID targetId = jdbc.queryForObject(
                "SELECT target_form_version_id FROM record_work_items WHERE id=? AND project_id=?",
                UUID.class, workId, projectId);
        if (manifest == null || !manifest.matchesScope(workId, sourceId, targetId,
                permitted.sourceXFormSha256(), permitted.targetXFormSha256()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Explicit mapping scope differs");
        var escalated = permitted.fields().stream().map(StaticTypedMappingFieldPolicy.Finding::targetPath)
                .collect(java.util.stream.Collectors.toSet());
        if (!escalated.equals(manifest.rules().stream().map(ExplicitMappingManifest.FieldRule::targetPath)
                .collect(java.util.stream.Collectors.toSet())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mapping destinations differ from reviewed scope");
        var proposal = jdbc.query("""
                SELECT manifest_sha256, manifest_canonical, recommendation_sha256, recorded_by
                FROM typed_mapping_explicit_proposals WHERE recommendation_id=?
                """, (rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class).toString()), recommendationId);
        boolean reviewerBound = !proposal.isEmpty();
        if (reviewerBound && (proposal.size() != 1 || !proposal.get(0).equals(List.of(
                manifest.sha256(), new String(manifest.canonicalBytes(), StandardCharsets.US_ASCII),
                permitted.recommendationEvidenceSha256(), permitted.originatingReviewerId().toString()))))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Explicit reviewer proposal differs");
        List<NativeTypedMappingSchemaProbe.ExplicitEvidence> proof;
        try {
            proof = NativeTypedMappingSchemaProbe.inspectExplicit(
                    forms.sourceXForm(), forms.targetXForm(), manifest, loader);
        } catch (IllegalArgumentException rejected) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Explicit schema inspection rejected");
        }
        return new ExplicitInspection(projectId, workId, recommendationId,
                permitted.originatingReviewerId(), permitted.independentSignerId(),
                permitted.recommendationEvidenceSha256(), manifest.sha256(), proof,
                reviewerBound ? "REVIEWER_BOUND_NATIVE_SCHEMA_ONLY_NO_GRANT" :
                        "ADVISORY_EXPLICIT_NATIVE_SCHEMA_ONLY_NO_GRANT",
                false, false, false, false, false, false);
    }

    public record ExplicitEndorsementRequest(String recommendationEvidenceSha256,
            String manifestSha256, ExplicitMappingManifest manifest) { }
    public record ExplicitEndorsementReceipt(UUID recommendationId, UUID signerId,
            String evidenceSha256, String state, boolean canIssueGrant, boolean canTransfer) {
        public ExplicitEndorsementReceipt {
            if (canIssueGrant || canTransfer) throw new IllegalArgumentException("Endorsement is not authority");
        }
    }

    @Transactional
    public ExplicitEndorsementReceipt endorseExplicit(UUID projectId, UUID workId, UUID recommendationId,
            String authorization, ExplicitEndorsementRequest request) {
        // First authenticate before taking a lock; repeat policy checks under lock.
        staticPolicy.inspect(projectId, workId, recommendationId, authorization);
        TypedMappingRecommendationService.lockReadyWork(jdbc, projectId, workId);
        if (request == null || request.manifest() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Exact manifest required");
        var proof = inspectExplicit(projectId, workId, recommendationId, authorization, request.manifest());
        if (!"REVIEWER_BOUND_NATIVE_SCHEMA_ONLY_NO_GRANT".equals(proof.state()) ||
                !proof.manifestSha256().equals(request.manifestSha256()) ||
                !proof.recommendationEvidenceSha256().equals(request.recommendationEvidenceSha256()) ||
                proof.fields().isEmpty() || proof.fields().stream().anyMatch(
                    f -> !"NATIVE_EXPLICIT_CANDIDATE_ONLY".equals(f.state())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reviewer-bound native evidence required");
        TypedMappingRecommendationService.requireNoDenial(jdbc, recommendationId);
        String digest = sha256(String.join("\n", "DC_EXPLICIT_ENDORSEMENT_V1",
                projectId.toString(), workId.toString(), recommendationId.toString(),
                proof.originatingReviewerId().toString(), proof.independentSignerId().toString(),
                proof.recommendationEvidenceSha256(), proof.manifestSha256()));
        var expected = List.of(proof.manifestSha256(), proof.recommendationEvidenceSha256(),
                proof.originatingReviewerId().toString(), proof.independentSignerId().toString(), digest);
        var prior = jdbc.query("""
                SELECT manifest_sha256, recommendation_sha256, reviewer_id, signer_id, evidence_sha256
                FROM typed_mapping_explicit_endorsements WHERE recommendation_id=?
                """, (rs, n) -> List.of(rs.getString(1), rs.getString(2),
                        rs.getObject(3, UUID.class).toString(), rs.getObject(4, UUID.class).toString(),
                        rs.getString(5)), recommendationId);
        if (prior.isEmpty()) {
            jdbc.update("""
                    INSERT INTO typed_mapping_explicit_endorsements
                    (recommendation_id, manifest_sha256, recommendation_sha256, reviewer_id, signer_id, evidence_sha256)
                    VALUES (?,?,?,?,?,?)
                    """, recommendationId, proof.manifestSha256(), proof.recommendationEvidenceSha256(),
                    proof.originatingReviewerId(), proof.independentSignerId(), digest);
        } else if (prior.size() != 1 || !prior.get(0).equals(expected)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Explicit endorsement cannot be replaced");
        }
        return new ExplicitEndorsementReceipt(recommendationId, proof.independentSignerId(), digest,
                "RECORDED_NATIVE_COMPATIBILITY_ENDORSEMENT_NO_GRANT", false, false);
    }

    private ExactForms exactForms(UUID projectId, UUID workId, UUID recommendationId,
            TypedMappingPolicyInspectionService.CandidateInspection permitted) {
        // Read exact immutable historical/target form versions, not caller
        // supplied XForms. Work and recommendation are scoped in both joins.
        List<ExactForms> rows = jdbc.query("""
                SELECT historical.xml_content AS source_xform,
                       target.xml_content AS target_xform
                FROM record_work_items work
                JOIN typed_mapping_review_recommendations recommendation
                  ON recommendation.work_item_id = work.id
                 AND recommendation.project_id = work.project_id
                JOIN form_versions historical
                  ON historical.id = work.source_form_version_id
                JOIN form_versions target
                  ON target.id = work.target_form_version_id
                WHERE work.project_id = ? AND work.id = ?
                  AND work.state = 'READY' AND work.work_type = 'AMENDMENT'
                  AND recommendation.id = ?
                  AND recommendation.target_form_version_id = work.target_form_version_id
                """, (rs, idx) -> new ExactForms(
                    rs.getString("source_xform"),
                    rs.getString("target_xform")),
                projectId, workId, recommendationId);
        if (rows.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Current native review versions unavailable");
        }
        ExactForms forms = rows.get(0);
        if (!sha256(forms.sourceXForm()).equals(permitted.sourceXFormSha256()) ||
                !sha256(forms.targetXForm()).equals(permitted.targetXFormSha256())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Exact versions changed after independent reviewer inspection");
        }

        return forms;
    }

    private static String sha256(String text) {
        if (text == null || text.isBlank() || text.length() > 8_388_608) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Exact historical version unavailable");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
