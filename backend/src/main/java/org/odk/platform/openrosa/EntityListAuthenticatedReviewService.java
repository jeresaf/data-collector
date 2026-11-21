package org.odk.platform.openrosa;

import org.odk.platform.security.CurrentUserService;
import java.util.Objects;
import java.util.UUID;

/** Inactive principal-bound removal-review facade; no route or Spring bean. */
public final class EntityListAuthenticatedReviewService {
    private final CurrentUserService currentUserService;
    private final EntityListSnapshotWebAuthorization webAuthorization;
    private final JdbcEntityListSnapshotPermissionGate transactionAuthorization;
    private final JdbcEntityListRemovalReviewRegister register;
    private final JdbcEntityListDenialEvidenceWriter denials;

    public EntityListAuthenticatedReviewService(
            CurrentUserService currentUserService,
            EntityListSnapshotWebAuthorization webAuthorization,
            JdbcEntityListSnapshotPermissionGate transactionAuthorization,
            JdbcEntityListRemovalReviewRegister register,
            JdbcEntityListDenialEvidenceWriter denials) {
        this.currentUserService = Objects.requireNonNull(currentUserService);
        this.webAuthorization = Objects.requireNonNull(webAuthorization);
        this.transactionAuthorization = Objects.requireNonNull(transactionAuthorization);
        this.register = Objects.requireNonNull(register);
        this.denials = Objects.requireNonNull(denials);
    }

    private boolean deny(UUID reviewId, UUID actor, JdbcEntityListDenialEvidenceWriter.Reason reason) {
        denials.record(reviewId, actor,
                JdbcEntityListDenialEvidenceWriter.Operation.REVIEW_DECISION, reason);
        return false;
    }

    public boolean decide(UUID reviewId, JdbcEntityListRemovalReviewRegister.Decision decision) {
        if (reviewId == null) {
            return false; // No review identifier exists to correlate an audit event.
        }
        var current = currentUserService.getCurrentWebUser();
        if (current.isEmpty() || current.get().getId() == null) {
            return deny(reviewId, null, JdbcEntityListDenialEvidenceWriter.Reason.UNAUTHENTICATED);
        }
        UUID reviewer = current.get().getId();
        if (!Boolean.TRUE.equals(current.get().getIsActive())) {
            return deny(reviewId, reviewer, JdbcEntityListDenialEvidenceWriter.Reason.INACTIVE_ACCOUNT);
        }
        if (decision == null) {
            return deny(reviewId, reviewer, JdbcEntityListDenialEvidenceWriter.Reason.INVALID_STATE);
        }
        var request = register.get(reviewId);
        if (request == null || !"PENDING".equals(request.state())) {
            return deny(reviewId, reviewer, JdbcEntityListDenialEvidenceWriter.Reason.INVALID_STATE);
        }
        if (reviewer.equals(request.requester())) {
            return deny(reviewId, reviewer, JdbcEntityListDenialEvidenceWriter.Reason.SELF_ACTION);
        }
        if (!webAuthorization.mayInitialize(reviewer, request.key())) {
            return deny(reviewId, reviewer, JdbcEntityListDenialEvidenceWriter.Reason.PERMISSION_DENIED);
        }
        boolean accepted = register.decideAuthorized(
                reviewId, reviewer, decision, transactionAuthorization);
        return accepted || deny(reviewId, reviewer,
                JdbcEntityListDenialEvidenceWriter.Reason.PERMISSION_DENIED);
    }
}
