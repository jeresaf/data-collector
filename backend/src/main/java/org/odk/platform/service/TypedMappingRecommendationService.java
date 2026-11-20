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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M3D4B2A: independent reviewer RECOMMENDATIONS, not mapping grants.
 *
 * An authenticated web reviewer may record a conservative, append-only
 * recommendation for a READY amendment. A recommendation cannot read old
 * answers, authorize prefill, issue tokens, authorize a transfer, or submit.
 *
 * This is not the final signed authority ledger. Database administrators
 * can still alter rows; SQL immutability and signer trust are release gates.
 */
@Service
@RequiredArgsConstructor
public class TypedMappingRecommendationService {
    private final JdbcTemplate jdbc;
    private final TypedMappingReviewerEligibilityService reviewerEligibility;

    public enum Disposition { REENTER, LEAVE_EMPTY, ESCALATE_FOR_RULE_REVIEW }
    public enum ReasonCode {
        REENTER_REQUIRED, EXPLICIT_RULE_REVIEW_NEEDED, DO_NOT_TRANSFER
    }

    public record RecommendationRequest(
            String clientRequestId, ReasonCode reasonCode,
            Map<String, Disposition> fieldDecisions,
            String sourceXmlSha256, String sourceXFormSha256,
            String targetXFormSha256) { }

    public record RecommendationContext(
            UUID projectId, UUID workId, UUID reviewerId,
            UUID sourceSubmissionId, UUID targetFormVersionId, String sourceInstanceId,
            String sourceXmlSha256, String sourceXFormSha256, String targetXFormSha256,
            String state, boolean canApproveMapping, boolean canTransfer,
            boolean canSubmit) {
        public RecommendationContext {
            if (canApproveMapping || canTransfer || canSubmit) {
                throw new IllegalArgumentException("Reviewer context cannot grant migration rights");
            }
        }
    }

    public record RecommendationReceipt(
            UUID recommendationId, UUID projectId, UUID workId, UUID reviewerId,
            String evidenceSha256, String state, int reviewedPathCount,
            boolean canApproveMapping, boolean canReadHistoricalAnswers,
            boolean canTransfer, boolean canSubmit) {
        public RecommendationReceipt {
            if (canApproveMapping || canReadHistoricalAnswers || canTransfer || canSubmit) {
                throw new IllegalArgumentException("Recommendation cannot grant migration rights");
            }
        }
    }

    private record SourceData(
            UUID sourceSubmissionId, UUID targetFormVersionId, String sourceInstanceId,
            String sourceXml, String sourceXForm, String targetXForm) { }

    private record Existing(UUID id, String hash, ReasonCode reasonCode) { }

    private static final Pattern SAFE_PATH = Pattern.compile(
            "/[A-Za-z_][A-Za-z0-9_.-]*(/[A-Za-z_][A-Za-z0-9_.-]*){1,11}");
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    @Transactional(readOnly = true)
    public RecommendationContext context(
            UUID projectId, UUID workId, String authorization) {
        var reviewer = reviewerEligibility.inspect(projectId, workId, authorization);
        SourceData data = sourceData(projectId, workId);
        return new RecommendationContext(
                projectId, workId, reviewer.reviewerId(),
                data.sourceSubmissionId(), data.targetFormVersionId(),
                data.sourceInstanceId(), sha256(data.sourceXml()),
                sha256(data.sourceXForm()), sha256(data.targetXForm()),
                "REVIEW_RECOMMENDATION_ONLY", false, false, false);
    }

    /**
     * Acquires the current READY work row lock, checks current role/identity and
     * exact source/target fingerprints, then atomically inserts recommendation
     * header and normalized field dispositions. Same idempotency key with
     * different content is always 409, never an overwrite.
     */
    @Transactional
    public RecommendationReceipt record(
            UUID projectId, UUID workId, String authorization,
            RecommendationRequest request) {
        if (request == null || request.clientRequestId() == null ||
                !SAFE_KEY.matcher(request.clientRequestId()).matches() ||
                request.reasonCode() == null || request.fieldDecisions() == null ||
                request.fieldDecisions().isEmpty() ||
                request.fieldDecisions().size() > 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A bounded reviewer recommendation is required");
        }
        Map<String, Disposition> decisions = new TreeMap<>();
        for (var entry : request.fieldDecisions().entrySet()) {
            String path = entry.getKey();
            if (path == null || path.length() > 255 ||
                    !SAFE_PATH.matcher(path).matches() || entry.getValue() == null ||
                    decisions.put(path, entry.getValue()) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid reviewer path decision");
            }
        }
        boolean escalating = decisions.containsValue(Disposition.ESCALATE_FOR_RULE_REVIEW);
        if ((request.reasonCode() == ReasonCode.EXPLICIT_RULE_REVIEW_NEEDED) != escalating) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Escalation reason and field decisions disagree");
        }
        if (!List.of(request.sourceXmlSha256(), request.sourceXFormSha256(),
                request.targetXFormSha256()).stream().allMatch(
                        value -> value != null && SHA256.matcher(value).matches())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Exact source and target fingerprints are required");
        }

        // The lock serializes completion of the same work with this new
        // recommendation operation. It does NOT lock source/form mutations,
        // which must be rechecked by eventual grant/submit authority.
        List<UUID> locked = jdbc.query("""
                SELECT id FROM record_work_items
                WHERE id = ? AND project_id = ? AND state = 'READY'
                FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), workId, projectId);
        if (locked.size() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Work is no longer eligible for review");
        }

        RecommendationContext live = context(projectId, workId, authorization);
        if (!live.sourceXmlSha256().equals(request.sourceXmlSha256()) ||
                !live.sourceXFormSha256().equals(request.sourceXFormSha256()) ||
                !live.targetXFormSha256().equals(request.targetXFormSha256())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Historical record or form versions changed");
        }

        String digest = digest(live, request.clientRequestId(),
                request.reasonCode(), decisions);
        List<Existing> prior = jdbc.query("""
                SELECT id, evidence_sha256, reason_code
                FROM typed_mapping_review_recommendations
                WHERE work_item_id = ? AND reviewer_user_id = ? AND client_request_id = ?
                """, (rs, row) -> new Existing(
                rs.getObject("id", UUID.class),
                rs.getString("evidence_sha256"),
                ReasonCode.valueOf(rs.getString("reason_code"))),
                workId, live.reviewerId(), request.clientRequestId());
        if (!prior.isEmpty()) {
            Existing existing = prior.get(0);
            Map<String, Disposition> stored = storedDecisions(existing.id());
            if (!existing.hash().equals(digest) ||
                    existing.reasonCode() != request.reasonCode() ||
                    !stored.equals(decisions)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Reviewer idempotency key cannot be reused with changed decisions");
            }
            return receipt(existing.id(), live, digest, stored.size());
        }

        UUID recommendationId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO typed_mapping_review_recommendations
                    (id, project_id, work_item_id, reviewer_user_id,
                     client_request_id, reason_code, source_submission_id,
                     source_instance_id, target_form_version_id,
                     source_xml_sha256, source_xform_sha256, target_xform_sha256,
                     evidence_sha256)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    recommendationId, projectId, workId, live.reviewerId(),
                    request.clientRequestId(), request.reasonCode().name(),
                    live.sourceSubmissionId(), live.sourceInstanceId(),
                    live.targetFormVersionId(), live.sourceXmlSha256(),
                    live.sourceXFormSha256(), live.targetXFormSha256(), digest);
            for (var decision : decisions.entrySet()) {
                jdbc.update("""
                        INSERT INTO typed_mapping_review_fields
                        (recommendation_id, target_path, disposition)
                        VALUES (?, ?, ?)
                        """, recommendationId, decision.getKey(), decision.getValue().name());
            }
        } catch (DuplicateKeyException exception) {
            // An independent transaction may have committed the same key.
            // Fail closed instead of merging or overwriting decisions.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Concurrent reviewer recommendation already recorded", exception);
        }

        if (!storedDecisions(recommendationId).equals(decisions)) {
            throw new IllegalStateException("Stored reviewer evidence differs from request");
        }
        return receipt(recommendationId, live, digest, decisions.size());
    }

    private SourceData sourceData(UUID projectId, UUID workId) {
        List<SourceData> matches = jdbc.query("""
                SELECT w.source_submission_id, w.source_instance_id, w.target_form_version_id,
                       src.xml_content AS source_xml,
                       historic.xml_content AS source_xform,
                       target.xml_content AS target_xform
                FROM record_work_items w
                JOIN submissions src ON src.id = w.source_submission_id
                JOIN form_versions historic ON historic.id = w.source_form_version_id
                JOIN form_versions target ON target.id = w.target_form_version_id
                JOIN xforms form ON form.id = src.form_id
                WHERE w.project_id = ? AND w.id = ?
                  AND w.state = 'READY' AND w.work_type = 'AMENDMENT'
                  AND src.instance_id = w.source_instance_id
                  AND src.form_version_id = historic.id
                  AND historic.form_id = src.form_id
                  AND target.form_id = src.form_id
                  AND form.project_id = w.project_id
                  AND form.is_published = TRUE AND form.is_active = TRUE
                  AND target.is_published = TRUE
                  AND src.xml_content IS NOT NULL AND LENGTH(TRIM(src.xml_content)) > 0
                  AND historic.xml_content IS NOT NULL
                      AND LENGTH(TRIM(historic.xml_content)) > 0
                  AND target.xml_content IS NOT NULL
                      AND LENGTH(TRIM(target.xml_content)) > 0
                """, (rs, row) -> new SourceData(
                    rs.getObject("source_submission_id", UUID.class),
                    rs.getObject("target_form_version_id", UUID.class),
                    rs.getString("source_instance_id"),
                    rs.getString("source_xml"),
                    rs.getString("source_xform"),
                    rs.getString("target_xform")),
                projectId, workId);
        if (matches.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Eligible reviewer source context unavailable");
        }
        return matches.get(0);
    }

    private Map<String, Disposition> storedDecisions(UUID id) {
        Map<String, Disposition> fields = new LinkedHashMap<>();
        jdbc.query("""
                SELECT target_path, disposition
                FROM typed_mapping_review_fields
                WHERE recommendation_id = ?
                ORDER BY target_path
                """, (ResultSet rs) -> {
            String path = rs.getString("target_path");
            Disposition disposition = Disposition.valueOf(rs.getString("disposition"));
            if (fields.put(path, disposition) != null) {
                throw new IllegalStateException("Duplicate stored field review decision");
            }
        }, id);
        return fields;
    }

    private RecommendationReceipt receipt(
            UUID id, RecommendationContext ctx, String digest, int count) {
        return new RecommendationReceipt(id, ctx.projectId(), ctx.workId(),
                ctx.reviewerId(), digest, "RECORDED_NON_AUTHORITATIVE", count,
                false, false, false, false);
    }

    private static String sha256(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Missing authenticated schema or source");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String digest(
            RecommendationContext ctx, String key, ReasonCode reason,
            Map<String, Disposition> decisions) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String value : List.of("DC_TYPED_REVIEW_RECOMMENDATION_V1",
                    ctx.projectId().toString(), ctx.workId().toString(),
                    ctx.reviewerId().toString(), ctx.sourceSubmissionId().toString(),
                    ctx.sourceInstanceId(), ctx.targetFormVersionId().toString(),
                    ctx.sourceXmlSha256(), ctx.sourceXFormSha256(),
                    ctx.targetXFormSha256(), key, reason.name())) {
                updatePart(md, value);
            }
            for (var entry : decisions.entrySet()) {
                updatePart(md, entry.getKey());
                updatePart(md, entry.getValue().name());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void updatePart(MessageDigest md, String value) {
        byte[] part = value.getBytes(StandardCharsets.UTF_8);
        md.update(ByteBuffer.allocate(4).putInt(part.length).array());
        md.update(part);
    }
}
