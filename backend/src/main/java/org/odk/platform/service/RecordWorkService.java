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
            boolean mappingApprovalRequired, String submissionSemantics) { }

    public record RecordSummary(
            UUID submissionId, String instanceId, UUID formId,
            UUID formVersionId, Instant submittedAt) { }

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
        return new Preparation(
                work, source.getXmlContent(), target.getXmlContent(),
                target.getForm().getXmlns(), target.getVersion(), true,
                work.workType() == WorkType.AMENDMENT
                  ? "Use a NEW instanceID and deprecatedID referencing sourceInstanceId; review all mappings"
                  : "Use a NEW instanceID WITHOUT deprecatedID; this is a separate visit");
    }

    @Transactional(readOnly = true)
    public List<WorkView> pending(UUID projectId) {
        AppUser actor = currentAppUser();
        requireActiveProject(projectId);
        return jdbc.query("""
                SELECT * FROM record_work_items
                WHERE project_id = ? AND assigned_app_user_id = ? AND state = 'READY'
                ORDER BY created_at DESC LIMIT 100
                """, this::fromRow, projectId, actor.getId());
    }

    @Transactional(readOnly = true)
    public List<RecordSummary> ownedRecords(UUID projectId) {
        AppUser actor = currentAppUser();
        requireActiveProject(projectId);
        List<UUID> assigned = appUserProjectRepository.findAssignedFormIdsByAppUserIdAndProjectId(
                actor.getId(), projectId);
        if (assigned.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT s.id, s.instance_id, s.form_id, s.form_version_id, s.submitted_at
                FROM submissions s JOIN xforms f ON f.id = s.form_id
                WHERE f.project_id = ? AND s.submitted_by_id = ?
                ORDER BY s.submitted_at DESC LIMIT 100
                """, (rs, row) -> {
            UUID formId = rs.getObject("form_id", UUID.class);
            if (!assigned.contains(formId)) return null;
            var at = rs.getTimestamp("submitted_at");
            return new RecordSummary(rs.getObject("id", UUID.class),
                    rs.getString("instance_id"), formId,
                    rs.getObject("form_version_id", UUID.class),
                    at == null ? null : at.toInstant());
        }, projectId, actor.getId()).stream().filter(Objects::nonNull).toList();
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
