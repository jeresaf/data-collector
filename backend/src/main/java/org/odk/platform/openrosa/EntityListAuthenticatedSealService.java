package org.odk.platform.openrosa;

import org.odk.platform.security.CurrentUserService;
import java.util.Objects;
import java.util.UUID;

/** Inactive principal-bound approval-readiness facade; cannot publish or delete. */
public final class EntityListAuthenticatedSealService {
    private final CurrentUserService currentUserService;
    private final EntityListSnapshotWebAuthorization webAuthorization;
    private final JdbcEntityListSnapshotPermissionGate transactionAuthorization;
    private final JdbcEntityListRemovalReviewRegister register;
    private final JdbcEntityListRemovalApprovalSeal seal;
    private final JdbcEntityListDenialEvidenceWriter denials;

    public EntityListAuthenticatedSealService(
            CurrentUserService currentUserService,
            EntityListSnapshotWebAuthorization webAuthorization,
            JdbcEntityListSnapshotPermissionGate transactionAuthorization,
            JdbcEntityListRemovalReviewRegister register,
            JdbcEntityListRemovalApprovalSeal seal,
            JdbcEntityListDenialEvidenceWriter denials) {
        this.currentUserService = Objects.requireNonNull(currentUserService);
        this.webAuthorization = Objects.requireNonNull(webAuthorization);
        this.transactionAuthorization = Objects.requireNonNull(transactionAuthorization);
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
            return false; // Cannot correlate an event with no review ID.
        }
        var current = currentUserService.getCurrentWebUser();
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
        var review = register.get(reviewId);
        if (review == null || !"APPROVED".equals(review.state()) || review.reviewer() == null) {
            return deny(reviewId, executor, JdbcEntityListDenialEvidenceWriter.Reason.INVALID_STATE);
        }
        if (executor.equals(review.requester()) || executor.equals(review.reviewer())) {
            return deny(reviewId, executor, JdbcEntityListDenialEvidenceWriter.Reason.SELF_ACTION);
        }
        if (!webAuthorization.mayInitialize(executor, review.key())) {
            return deny(reviewId, executor, JdbcEntityListDenialEvidenceWriter.Reason.PERMISSION_DENIED);
        }
        boolean accepted = seal.validateAndSealAuthorized(
                reviewId, executor, candidate, transactionAuthorization);
        // Conservatively classify transaction-level failures. The low-level
        // validator returns boolean, not exact failure reasons.
        return accepted || deny(reviewId, executor,
                JdbcEntityListDenialEvidenceWriter.Reason.PERMISSION_DENIED);
    }
}
