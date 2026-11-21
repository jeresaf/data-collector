package org.odk.platform.openrosa;

import org.odk.platform.security.CurrentUserService;
import java.util.Objects;
import java.util.UUID;

/** Inactive principal-bound approval-readiness facade; cannot publish or delete. */
public final class EntityListAuthenticatedSealService {
    private final CurrentUserService currentUserService;
    private final EntityListSnapshotWebAuthorization webAuthorization;
    private final JdbcEntityListRemovalRoleGate transactionAuthorization;
    private final JdbcEntityListRemovalReviewRegister register;
    private final JdbcEntityListRemovalApprovalSeal seal;
    private final JdbcEntityListDenialEvidenceWriter denials;

    public EntityListAuthenticatedSealService(
            CurrentUserService currentUserService,
            EntityListSnapshotWebAuthorization webAuthorization,
            JdbcEntityListRemovalRoleGate transactionAuthorization,
            JdbcEntityListRemovalReviewRegister register,
            JdbcEntityListRemovalApprovalSeal seal,
            JdbcEntityListDenialEvidenceWriter denials) {
        this.currentUserService = Objects.requireNonNull(currentUserService);
        this.webAuthorization = Objects.requireNonNull(webAuthorization);
        this.transactionAuthorization = Objects.requireNonNull(transactionAuthorization);
        if (transactionAuthorization.role() != JdbcEntityListRemovalRoleGate.Role.EXECUTOR) {
            throw new IllegalArgumentException("Incorrect removal delegation role for service");
        }
        this.register = Objects.requireNonNull(register);
        this.seal = Objects.requireNonNull(seal);
        this.denials = Objects.requireNonNull(denials);
    }

    private boolean deny(UUID reviewId, UUID actor, JdbcEntityListDenialEvidenceWriter.Reason reason) {
        denials.record(reviewId, actor,
                JdbcEntityListDenialEvidenceWriter.Operation.APPROVAL_VALIDATION, reason);
        return false;
    }

    public boolean validateApprovedReview(
            UUID reviewId, EntityListSnapshotLedgerCoordinator.Head candidate) {
        if (reviewId == null) {
            return deny(null, null, JdbcEntityListDenialEvidenceWriter.Reason.INVALID_STATE);
        }
        var current = EntityListDenialExceptionAudit.call(denials, reviewId, null,
                JdbcEntityListDenialEvidenceWriter.Operation.APPROVAL_VALIDATION,
                currentUserService::getCurrentWebUser);
        if (current.isEmpty() || current.get().getId() == null) {
            return deny(reviewId, null, JdbcEntityListDenialEvidenceWriter.Reason.UNAUTHENTICATED);
        }
        UUID executor = current.get().getId();
        if (!Boolean.TRUE.equals(current.get().getIsActive())) {
            return deny(reviewId, executor, JdbcEntityListDenialEvidenceWriter.Reason.INACTIVE_ACCOUNT);
        }
        if (candidate == null || candidate.evidence() == null) {
            return deny(reviewId, executor, JdbcEntityListDenialEvidenceWriter.Reason.EVIDENCE_MISMATCH);
        }
        var review = EntityListDenialExceptionAudit.call(denials, reviewId, executor,
                JdbcEntityListDenialEvidenceWriter.Operation.APPROVAL_VALIDATION,
                () -> register.get(reviewId));
        if (review == null || !"APPROVED".equals(review.state()) || review.reviewer() == null) {
            return deny(reviewId, executor, JdbcEntityListDenialEvidenceWriter.Reason.INVALID_STATE);
        }
        if (executor.equals(review.requester()) || executor.equals(review.reviewer())) {
            return deny(reviewId, executor, JdbcEntityListDenialEvidenceWriter.Reason.SELF_ACTION);
        }
        if (!EntityListDenialExceptionAudit.call(denials, reviewId, executor,
                JdbcEntityListDenialEvidenceWriter.Operation.APPROVAL_VALIDATION,
                () -> webAuthorization.mayInitialize(executor, review.key()))) {
            return deny(reviewId, executor, JdbcEntityListDenialEvidenceWriter.Reason.PERMISSION_DENIED);
        }
        var outcome = EntityListDenialExceptionAudit.call(denials, reviewId, executor,
                JdbcEntityListDenialEvidenceWriter.Operation.APPROVAL_VALIDATION,
                () -> seal.validateAndSealAuthorizedOutcome(
                        reviewId, executor, candidate, transactionAuthorization));
        if (outcome == JdbcEntityListRemovalApprovalSeal.ValidationOutcome.SEALED) {
            return true;
        }
        JdbcEntityListDenialEvidenceWriter.Reason reason = switch (outcome) {
            case INVALID_STATE -> JdbcEntityListDenialEvidenceWriter.Reason.INVALID_STATE;
            case SELF_ACTION -> JdbcEntityListDenialEvidenceWriter.Reason.SELF_ACTION;
            case EVIDENCE_MISMATCH -> JdbcEntityListDenialEvidenceWriter.Reason.EVIDENCE_MISMATCH;
            case PERMISSION_DENIED -> JdbcEntityListDenialEvidenceWriter.Reason.PERMISSION_DENIED;
            case STALE_HEAD -> JdbcEntityListDenialEvidenceWriter.Reason.STALE_HEAD;
            case REPLAY -> JdbcEntityListDenialEvidenceWriter.Reason.REPLAY;
            case SEALED -> throw new IllegalStateException("Successful seal cannot be denied");
        };
        return deny(reviewId, executor, reason);
    }
}
