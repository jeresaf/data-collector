package org.odk.platform.controller;

import lombok.RequiredArgsConstructor;
import org.odk.platform.service.RecordWorkService;
import org.odk.platform.service.RecordWorkService.CreateWork;
import org.odk.platform.service.RecordWorkService.Preparation;
import org.odk.platform.service.RecordWorkService.RecordSummary;
import org.odk.platform.service.RecordWorkService.WorkView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Mobile-compatible, project-scoped endpoint for records and two explicit
 * workflows. Access is enforced in RecordWorkService, including ownership
 * and form assignments; neither JWT web users nor another enumerator can
 * read another user's raw XML through this endpoint.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}")
@RequiredArgsConstructor
public class RecordWorkController {

    private final RecordWorkService service;

    @GetMapping("/records")
    public List<RecordSummary> listMyRecords(@PathVariable UUID projectId) {
        return service.ownedRecords(projectId);
    }

    @GetMapping("/records/{sourceSubmissionId}/target-versions")
    public List<RecordWorkService.TargetVersion> targetVersions(
            @PathVariable UUID projectId, @PathVariable UUID sourceSubmissionId,
            @RequestParam RecordWorkService.WorkType workType) {
        return service.targetVersions(projectId, sourceSubmissionId, workType);
    }

    @GetMapping("/records/{sourceSubmissionId}/timeline")
    public List<RecordWorkService.RelatedRecord> timeline(
            @PathVariable UUID projectId, @PathVariable UUID sourceSubmissionId) {
        return service.timeline(projectId, sourceSubmissionId);
    }

    @GetMapping("/record-work-items")
    public List<WorkView> pending(@PathVariable UUID projectId) {
        return service.pending(projectId);
    }

    @PostMapping("/record-work-items")
    public ResponseEntity<WorkView> create(
            @PathVariable UUID projectId, @RequestBody CreateWork request) {
        return ResponseEntity.status(201).body(service.create(projectId, request));
    }

    /**
     * Read-only, explicit denial until a separate trusted mapping-rule
     * issuer, approval chain and expiry/revocation checks are implemented.
     */
    @GetMapping("/record-work-items/{workId}/typed-mapping-authority")
    public RecordWorkService.TypedMappingAuthority typedMappingAuthority(
            @PathVariable UUID projectId, @PathVariable UUID workId) {
        return service.typedMappingAuthority(projectId, workId);
    }

    /** Owner-scoped digest-only evidence. Never retries or modifies a submission. */
    @GetMapping("/record-work-items/{workId}/completion-evidence")
    public ResponseEntity<RecordWorkService.CompletionEvidence> completionEvidence(
            @PathVariable UUID projectId, @PathVariable UUID workId) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(service.completionEvidence(projectId, workId));
    }

    @PostMapping("/record-work-items/{workId}/protected-attempt")
    public ResponseEntity<RecordWorkService.AttemptView> reserveAttempt(
            @PathVariable UUID projectId, @PathVariable UUID workId,
            @RequestBody RecordWorkService.ReserveAttempt request) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(service.reserveAttempt(projectId, workId, request));
    }

    @PostMapping("/record-work-items/{workId}/protected-attempt/{attemptId}/commitment")
    public ResponseEntity<RecordWorkService.AttemptView> bindAttempt(
            @PathVariable UUID projectId, @PathVariable UUID workId, @PathVariable UUID attemptId,
            @RequestBody RecordWorkService.BindAttempt request) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(service.bindAttempt(projectId, workId, attemptId, request));
    }

    @GetMapping("/record-work-items/{workId}/protected-attempt/{attemptId}")
    public ResponseEntity<RecordWorkService.AttemptStatus> attemptStatus(
            @PathVariable UUID projectId, @PathVariable UUID workId, @PathVariable UUID attemptId) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(service.protectedAttemptStatus(projectId, workId, attemptId));
    }

    @GetMapping("/record-work-items/{workId}/prepare")
    public Preparation prepare(@PathVariable UUID projectId,
                               @PathVariable UUID workId) {
        return service.prepare(projectId, workId);
    }
}
