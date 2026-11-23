package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.AppUserProjectRepository;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.SubmissionRepository;
import org.odk.platform.security.CurrentUserService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-owned, owner-scoped work orders for two different operations:
 * AMENDMENT: submit a revision (deprecatedID) against an optionally newer
 * form version; FOLLOW_UP: submit a new instance without deprecatedID.
 *
 * No existing submission XML, instance ID, or attachment is overwritten.
 * A mapping/preview is required before a newer-form draft can be accepted as
 * an accurate transfer of old answers.
 */
@Service
@RequiredArgsConstructor
public class RecordWorkService {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;
    private final ProjectRepository projectRepository;
    private final SubmissionRepository submissionRepository;
    private final FormVersionRepository formVersionRepository;
    private final AppUserProjectRepository appUserProjectRepository;

    public enum WorkType { AMENDMENT, FOLLOW_UP }

    public record CreateWork(
            UUID sourceSubmissionId, UUID targetFormVersionId, WorkType workType,
            String clientRequestId, String visitKey, Instant plannedFor) { }

    public record WorkView(
            UUID id, UUID sourceSubmissionId, String sourceInstanceId,
            UUID sourceFormVersionId, UUID targetFormVersionId,
            WorkType workType, String state, String visitKey,
            UUID completedSubmissionId) { }

    public record Preparation(
            WorkView work, String sourceXml, String targetXFormXml,
            String targetFormId, String targetFormVersion,
            boolean mappingApprovalRequired, String submissionSemantics,
            // Historical, exact persisted XForm. Null means unmappable legacy
            // record: callers MUST NOT infer its schema from the target version.
            String sourceXFormXml,
            // Read-only schema preview. This never grants historic-answer
            // transfer, even when all bind paths/types appear unchanged.
            FormVersionSchemaDiff.Report compatibility) { }

    /**
     * The authorized target-version catalog is read-only. The client must not
     * guess target version IDs or use unassigned/draft questionnaires.
     */
    public record TargetVersion(
            UUID targetFormVersionId, UUID formId, String formTitle,
            String version, boolean latest, boolean sameFormAsSource) { }

    public record RecordSummary(
            UUID submissionId, String instanceId, UUID formId,
            UUID formVersionId, Instant submittedAt,
            // An archived revision is historical. Clients must never infer
            // that this source remains current merely from its own XML.
            boolean amendmentSuperseded) { }

    public record RelatedRecord(
            UUID submissionId, String instanceId, UUID formId,
            UUID formVersionId, WorkType relationship,
            String visitKey, Instant submittedAt) { }

    private AppUser currentAppUser() {
        var principal = currentUserService.getCurrentUser().orElse(null);
        if (!(principal instanceof AppUser user) ||
                !Boolean.TRUE.equals(user.getIsActive()) ||
                !Boolean.TRUE.equals(user.getIsVerified())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Active App User required");
        }
        return user;
    }

    private void requireActiveProject(UUID projectId) {
        if (projectRepository.findById(projectId)
                .filter(p -> Boolean.TRUE.equals(p.getIsActive())).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
    }

    private void requireFormAssignment(AppUser actor, UUID projectId, UUID formId) {
        if (!appUserProjectRepository.findAssignedFormIdsByAppUserIdAndProjectId(
                actor.getId(), projectId).contains(formId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Form assignment required");
        }
    }

    private Submission accessibleSource(AppUser actor, UUID projectId, UUID submissionId) {
        Submission source = submissionRepository.findByIdWithForm(submissionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found"));
        if (source.getForm() == null || source.getForm().getProject() == null ||
                !projectId.equals(source.getForm().getProject().getId()) ||
                source.getSubmittedBy() == null ||
                !actor.getId().equals(source.getSubmittedBy().getId())) {
            // Conservative first milestone: the original submitter can access the
            // record. Cross-enumerator delegation must use explicit assignments later.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found");
        }
        requireFormAssignment(actor, projectId, source.getForm().getId());
        return source;
    }

    private FormVersion targetVersion(AppUser actor, UUID projectId, UUID versionId) {
        FormVersion v = formVersionRepository.findByIdWithFormFetch(versionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Target version not found"));
        XForm f = v.getForm();
        if (f.getProject() == null || !projectId.equals(f.getProject().getId()) ||
                !Boolean.TRUE.equals(f.getIsActive()) ||
                !Boolean.TRUE.equals(f.getIsPublished()) ||
                !Boolean.TRUE.equals(v.getIsPublished()) ||
                v.getXmlContent() == null || v.getXmlContent().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Published target version not found");
        }
        requireFormAssignment(actor, projectId, f.getId());
        return v;
    }

    private WorkView fromRow(ResultSet rs, int row) throws SQLException {
        return new WorkView(
                rs.getObject("id", UUID.class),
                rs.getObject("source_submission_id", UUID.class),
                rs.getString("source_instance_id"),
                rs.getObject("source_form_version_id", UUID.class),
                rs.getObject("target_form_version_id", UUID.class),
                WorkType.valueOf(rs.getString("work_type")),
                rs.getString("state"),
                rs.getString("visit_key"),
                rs.getObject("completed_submission_id", UUID.class));
    }

    @Transactional
    public WorkView create(UUID projectId, CreateWork request) {
        AppUser actor = currentAppUser();
        requireActiveProject(projectId);
        if (request == null || request.sourceSubmissionId() == null ||
                request.targetFormVersionId() == null || request.workType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source, target and workflow required");
        }
        if (request.clientRequestId() == null ||
                !request.clientRequestId().matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid clientRequestId required");
        }
        if (request.workType() == WorkType.FOLLOW_UP &&
                (request.visitKey() == null ||
                 !request.visitKey().matches("[A-Za-z0-9._:-]{1,128}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Follow-up visitKey required");
        }
        if (request.workType() == WorkType.AMENDMENT &&
                request.visitKey() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amendments do not create visits");
        }

        Submission source = accessibleSource(actor, projectId, request.sourceSubmissionId());
        // A source row is immutable even after it is amended. Do not allow a
        // newly created offline task to fork that historical revision. Lock
        // the exact original row to serialize this check with OpenRosa's
        // replacement/archive transaction; the subsequent SELECT sees the
        // committed history after any in-flight replacement finishes.
        if (request.workType() == WorkType.AMENDMENT) {
            jdbc.queryForObject("SELECT id FROM submissions WHERE id = ? FOR UPDATE",
                    UUID.class, source.getId());
            Boolean alreadyAmended = jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM submission_versions "
                            + "WHERE submission_id = ?)",
                    Boolean.class, source.getId());
            if (Boolean.TRUE.equals(alreadyAmended)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Source was already amended; select the latest authorized revision");
            }
        }
        FormVersion target = targetVersion(actor, projectId, request.targetFormVersionId());
        if (request.workType() == WorkType.AMENDMENT &&
                !Objects.equals(source.getForm().getId(), target.getForm().getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An amendment must use a version of the original form");
        }

        List<WorkView> existing = jdbc.query("""
                SELECT * FROM record_work_items
                WHERE project_id = ? AND assigned_app_user_id = ? AND client_request_id = ?
                """, this::fromRow, projectId, actor.getId(), request.clientRequestId());
        if (!existing.isEmpty()) {
            WorkView item = existing.get(0);
            if (item.sourceSubmissionId().equals(request.sourceSubmissionId()) &&
                    item.targetFormVersionId().equals(request.targetFormVersionId()) &&
                    item.workType() == request.workType() &&
                    Objects.equals(item.visitKey(), request.visitKey())) {
                return item;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key reused for another work item");
        }

        UUID id = UUID.randomUUID();
        UUID sourceVersionId = source.getFormVersion() == null ? null : source.getFormVersion().getId();
        try {
            jdbc.update("""
                    INSERT INTO record_work_items
                    (id, project_id, source_submission_id, source_form_version_id,
                     source_instance_id, target_form_version_id, assigned_app_user_id,
                     work_type, state, client_request_id, visit_key, planned_for)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?, ?, ?)
                    """,
                    id, projectId, source.getId(), sourceVersionId, source.getInstanceId(),
                    target.getId(), actor.getId(), request.workType().name(),
                    request.clientRequestId(), request.visitKey(),
                    request.plannedFor() == null ? null : java.sql.Timestamp.from(request.plannedFor()));
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A visit or idempotency key already exists", ex);
        }
        return getOwnedWork(actor, projectId, id);
    }

    private WorkView getOwnedWork(AppUser actor, UUID projectId, UUID id) {
        return jdbc.query("""
                SELECT * FROM record_work_items
                WHERE id = ? AND project_id = ? AND assigned_app_user_id = ?
                """, this::fromRow, id, projectId, actor.getId()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Work item not found"));
    }

    /**
     * A mobile draft may be prepared while offline. If another amendment was
     * committed against the same exact source after this work item was created,
     * its cached answers must NOT be submitted as though still current.
     *
     * Call this after locking the source row and BEFORE adding the incoming
     * submission or archiving its historical source. The work item creation
     * timestamp is a durable, DB-owned revision watermark; no phone clock or
     * caller-supplied token can move it forward.
     *
     * FOLLOW_UP observations remain independent visits, so this guard applies
     * only to AMENDMENT. Legacy OpenRosa edits without a server work ID are
     * supported separately and do not claim offline-snapshot qualification.
     */
    @Transactional
    public void requireFreshAmendmentWork(
            UUID projectId, AppUser actor, Submission original,
            FormVersion target, String taskId) {
        if (taskId == null || taskId.isBlank()) return;
        if (actor == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "App User required");
        }
        UUID id;
        try {
            id = UUID.fromString(taskId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid work item ID");
        }
        WorkView work = getOwnedWork(actor, projectId, id);
        if (work.workType() != WorkType.AMENDMENT) return;
        if (!"READY".equals(work.state()) ||
                target == null || !work.targetFormVersionId().equals(target.getId()) ||
                original == null || !work.sourceSubmissionId().equals(original.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Amendment work item or target has changed");
        }
        Submission source = accessibleSource(actor, projectId, work.sourceSubmissionId());
        if (!Objects.equals(source.getInstanceId(), work.sourceInstanceId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source record changed");
        }
        requireUnchangedSourceSinceWorkCreated(work.id(), source.getId());
    }

    private void requireUnchangedSourceSinceWorkCreated(UUID workId, UUID sourceId) {
        // Historical submissions themselves remain immutable. Their revision
        // archive is the durable server-side indicator of intervening edits.
        // Recheck under the original-submission row lock in the intake path.
        Boolean changed = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM submission_versions sv
                    JOIN record_work_items rw
                      ON rw.source_submission_id = sv.submission_id
                    WHERE rw.id = ? AND rw.source_submission_id = ?
                      AND sv.created_at >= rw.created_at
                )
                """, Boolean.class, workId, sourceId);
        if (Boolean.TRUE.equals(changed)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Source amended since work item was created; refresh the record");
        }
    }

    @Transactional(readOnly = true)
    public Preparation prepare(UUID projectId, UUID workId) {
        AppUser actor = currentAppUser();
        requireActiveProject(projectId);
        WorkView work = getOwnedWork(actor, projectId, workId);
        if (!"READY".equals(work.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Work item is not ready");
        }
        Submission source = accessibleSource(actor, projectId, work.sourceSubmissionId());
        targetVersion(actor, projectId, work.targetFormVersionId());
        if (!Objects.equals(source.getInstanceId(), work.sourceInstanceId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source record changed");
        }
        FormVersion target = targetVersion(actor, projectId, work.targetFormVersionId());
        if (work.workType() == WorkType.AMENDMENT) {
            requireUnchangedSourceSinceWorkCreated(work.id(), source.getId());
        }
        // A historical source version is not necessarily the current version.
        // Never substitute the target XML or current XForm for a missing source
        // schema: that could silently reinterpret legacy answers.
        String originalFormXml = null;
        UUID originalVersionId = source.getFormVersion() == null
                ? null : source.getFormVersion().getId();
        if (originalVersionId != null) {
            FormVersion original = formVersionRepository.findByIdWithFormFetch(originalVersionId)
                    .orElse(null);
            if (original != null &&
                    source.getForm().getId().equals(original.getForm().getId()) &&
                    original.getForm().getProject() != null &&
                    projectId.equals(original.getForm().getProject().getId()) &&
                    original.getXmlContent() != null && !original.getXmlContent().isBlank()) {
                // Reading historical definitions is allowed even if archived,
                // but only after proving the source record is owned/assigned.
                originalFormXml = original.getXmlContent();
            }
        }
        return new Preparation(
                work, source.getXmlContent(), target.getXmlContent(),
                target.getForm().getXmlns(), target.getVersion(), true,
                work.workType() == WorkType.AMENDMENT
                  ? "Use a NEW instanceID and deprecatedID referencing sourceInstanceId; review all mappings"
                  : "Use a NEW instanceID WITHOUT deprecatedID; this is a separate visit",
                originalFormXml,
                FormVersionSchemaDiff.compare(originalFormXml, target.getXmlContent()));
    }

    /**
     * 3B-4A authority discovery: a securely scoped, fail-closed server policy.
     * There is deliberately NO grant-issuance API or reusable authorization
     * token yet. A local Android review, even if encrypted, is not an
     * independently authorized mapping rule.
     */
    public record TypedMappingAuthority(
            UUID workId, UUID sourceSubmissionId, UUID targetFormVersionId,
            String sourceXmlSha256, String sourceXFormSha256,
            String targetXFormSha256, String state, List<String> approvedPaths,
            boolean canReadHistoricalAnswers, boolean canTransfer,
            boolean canSubmit) {
        public TypedMappingAuthority {
            if (!approvedPaths.isEmpty() || canReadHistoricalAnswers ||
                    canTransfer || canSubmit) {
                throw new IllegalArgumentException(
                        "Server cannot issue a mapping grant without trusted rule approval");
            }
        }
    }

    @Transactional(readOnly = true)
    public TypedMappingAuthority typedMappingAuthority(UUID projectId, UUID workId) {
        // Reuse the existing real Basic-auth AppUser identity and fresh READY,
        // project ownership, original record, form assignment and historical
        // form version validation. The returned object contains no source XML,
        // answer values, XForm definitions or edit/upload authorization.
        Preparation prepared = prepare(projectId, workId);
        WorkView work = prepared.work();
        String state = switch (work.workType()) {
            case FOLLOW_UP -> "DENIED_INDEPENDENT_VISIT";
            case AMENDMENT -> prepared.sourceXFormXml() == null
                    ? "DENIED_HISTORICAL_SCHEMA_UNAVAILABLE"
                    : "DENIED_TRUSTED_RULE_ISSUER_NOT_CONFIGURED";
        };
        return new TypedMappingAuthority(
                work.id(), work.sourceSubmissionId(), work.targetFormVersionId(),
                contentSha256(prepared.sourceXml()),
                prepared.sourceXFormXml() == null ? null :
                    contentSha256(prepared.sourceXFormXml()),
                contentSha256(prepared.targetXFormXml()),
                state, List.of(), false, false, false);
    }

    private static String contentSha256(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Verified document is missing");
        }
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM SHA-256 provider missing", ex);
        }
    }

    /**
     * Enumerates only published, active and form-assigned versions within the
     * source record's project. An amendment can only use the same form;
     * follow-up visits may use another assigned questionnaire.
     *
     * Ownership is checked on every call. The catalog contains no XML,
     * credentials, attachments or historical answer values.
     */
    @Transactional(readOnly = true)
    public List<TargetVersion> targetVersions(
            UUID projectId, UUID sourceSubmissionId, WorkType workType) {
        AppUser actor = currentAppUser();
        requireActiveProject(projectId);
        if (workType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Work type required");
        }
        Submission source = accessibleSource(actor, projectId, sourceSubmissionId);
        UUID sourceFormId = source.getForm().getId();

        return jdbc.query("""
                SELECT fv.id AS target_form_version_id, f.id AS form_id,
                       f.title AS form_title, fv.version AS form_version,
                       fv.is_latest AS is_latest
                FROM form_versions fv
                JOIN xforms f ON f.id = fv.form_id
                WHERE f.project_id = ?
                  AND f.is_active = true AND f.is_published = true
                  AND fv.is_published = true
                  AND fv.xml_content IS NOT NULL AND LENGTH(TRIM(fv.xml_content)) > 0
                  AND (? = 'FOLLOW_UP' OR f.id = ?)
                  AND EXISTS (
                      SELECT 1 FROM app_user_projects aup
                      WHERE aup.app_user_id = ? AND aup.project_id = ?
                        AND aup.form_id = f.id AND aup.status = 'ACTIVE'
                  )
                ORDER BY f.title ASC, fv.is_latest DESC, fv.created_at DESC
                LIMIT 100
                """, (rs, row) -> {
                    UUID formId = rs.getObject("form_id", UUID.class);
                    return new TargetVersion(
                            rs.getObject("target_form_version_id", UUID.class),
                            formId,
                            rs.getString("form_title"),
                            rs.getString("form_version"),
                            rs.getBoolean("is_latest"),
                            sourceFormId.equals(formId));
                }, projectId, workType.name(), sourceFormId,
                actor.getId(), projectId);
    }

    @Transactional(readOnly = true)
    public List<WorkView> pending(UUID projectId) {
        AppUser actor = currentAppUser();
        requireActiveProject(projectId);
        return jdbc.query("""
                SELECT rw.* FROM record_work_items rw
                JOIN submissions src ON src.id = rw.source_submission_id
                JOIN form_versions target ON target.id = rw.target_form_version_id
                WHERE rw.project_id = ? AND rw.assigned_app_user_id = ? AND rw.state = 'READY'
                  AND src.submitted_by_id = rw.assigned_app_user_id
                  AND EXISTS (SELECT 1 FROM app_user_projects aup
                      WHERE aup.app_user_id = rw.assigned_app_user_id
                        AND aup.project_id = rw.project_id
                        AND aup.form_id = src.form_id AND aup.status = 'ACTIVE')
                  AND EXISTS (SELECT 1 FROM app_user_projects aup
                      WHERE aup.app_user_id = rw.assigned_app_user_id
                        AND aup.project_id = rw.project_id
                        AND aup.form_id = target.form_id AND aup.status = 'ACTIVE')
                ORDER BY rw.created_at DESC LIMIT 100
                """, this::fromRow, projectId, actor.getId());
    }

    @Transactional(readOnly = true)
    public List<RecordSummary> ownedRecords(UUID projectId) {
        AppUser actor = currentAppUser();
        requireActiveProject(projectId);
        return jdbc.query("""
                SELECT s.id, s.instance_id, s.form_id, s.form_version_id, s.submitted_at,
                       EXISTS(SELECT 1 FROM submission_versions sv
                              WHERE sv.submission_id = s.id) AS amendment_superseded
                FROM submissions s JOIN xforms f ON f.id = s.form_id
                WHERE f.project_id = ? AND s.submitted_by_id = ?
                  AND EXISTS (SELECT 1 FROM app_user_projects aup
                      WHERE aup.project_id = f.project_id
                        AND aup.app_user_id = s.submitted_by_id
                        AND aup.form_id = s.form_id AND aup.status = 'ACTIVE')
                ORDER BY s.submitted_at DESC LIMIT 100
                """, (rs, row) -> {
            var at = rs.getTimestamp("submitted_at");
            return new RecordSummary(rs.getObject("id", UUID.class),
                    rs.getString("instance_id"), rs.getObject("form_id", UUID.class),
                    rs.getObject("form_version_id", UUID.class),
                    at == null ? null : at.toInstant(),
                    rs.getBoolean("amendment_superseded"));
        }, projectId, actor.getId());
    }

    @Transactional(readOnly = true)
    public List<RelatedRecord> timeline(UUID projectId, UUID sourceSubmissionId) {
        AppUser actor = currentAppUser();
        requireActiveProject(projectId);
        accessibleSource(actor, projectId, sourceSubmissionId);
        return jdbc.query("""
                SELECT child.id, child.instance_id, child.form_id,
                       child.form_version_id, child.submitted_at,
                       link.link_type, link.visit_key
                FROM record_links link
                JOIN submissions child ON child.id = link.child_submission_id
                WHERE link.project_id = ? AND link.source_submission_id = ?
                ORDER BY child.submitted_at ASC
                """, (rs, row) -> {
            var timestamp = rs.getTimestamp("submitted_at");
            return new RelatedRecord(
                    rs.getObject("id", UUID.class),
                    rs.getString("instance_id"),
                    rs.getObject("form_id", UUID.class),
                    rs.getObject("form_version_id", UUID.class),
                    WorkType.valueOf(rs.getString("link_type")),
                    rs.getString("visit_key"),
                    timestamp == null ? null : timestamp.toInstant());
        }, projectId, sourceSubmissionId);
    }

    /**
     * Called inside the intake transaction after the new submission has been
     * validated and persisted. A forged task header cannot link unrelated data.
     */
    @Transactional
    public void completeIfPresent(UUID projectId, AppUser actor,
                                  Submission sourceBeingReplaced, Submission submitted,
                                  FormVersion target, String taskId) {
        if (taskId == null || taskId.isBlank()) return;
        if (actor == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "App User required for linked work");
        }
        UUID id;
        try { id = UUID.fromString(taskId); }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid work item ID");
        }
        WorkView work = getOwnedWork(actor, projectId, id);
        if (!"READY".equals(work.state()) || !work.targetFormVersionId().equals(target.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Work item stale or target form changed");
        }
        Submission source = accessibleSource(actor, projectId, work.sourceSubmissionId());
        if (!Objects.equals(source.getInstanceId(), work.sourceInstanceId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source record changed");
        }
        if (work.workType() == WorkType.AMENDMENT) {
            if (sourceBeingReplaced == null || !sourceBeingReplaced.getId().equals(source.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Amendment must replace exactly its source");
            }
        } else if (sourceBeingReplaced != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Follow-up must not replace an earlier submission");
        }
        int changed = jdbc.update("""
                UPDATE record_work_items
                SET state = 'COMPLETED', completed_submission_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = 'READY' AND assigned_app_user_id = ?
                """, submitted.getId(), id, actor.getId());
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Work item already completed");
        }
        jdbc.update("""
                INSERT INTO record_links
                (id, project_id, source_submission_id, child_submission_id,
                 work_item_id, link_type, visit_key)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), projectId, source.getId(), submitted.getId(),
                id, work.workType().name(), work.visitKey());
    }
}
