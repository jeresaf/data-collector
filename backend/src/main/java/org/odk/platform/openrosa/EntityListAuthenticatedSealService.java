package org.odk.platform.openrosa;

import org.odk.platform.security.CurrentUserService;

import java.util.Objects;
import java.util.UUID;

/**
 * Inactive, non-Spring-bound execution-readiness facade.
 *
 * The executor identity is always resolved from the current active web
 * principal. Only the opt-in transactional seal writer is reachable after a
 * project/form permission check. This still creates only a validation receipt;
 * it cannot publish or remove any Entity List data.
 */
public final class EntityListAuthenticatedSealService {
    private final CurrentUserService currentUserService;
    private final EntityListSnapshotWebAuthorization webAuthorization;
    private final JdbcEntityListSnapshotPermissionGate transactionAuthorization;
    private final JdbcEntityListRemovalReviewRegister register;
    private final JdbcEntityListRemovalApprovalSeal seal;

    public EntityListAuthenticatedSealService(
            CurrentUserService currentUserService,
            EntityListSnapshotWebAuthorization webAuthorization,
            JdbcEntityListSnapshotPermissionGate transactionAuthorization,
            JdbcEntityListRemovalReviewRegister register,
            JdbcEntityListRemovalApprovalSeal seal) {
        this.currentUserService = Objects.requireNonNull(currentUserService);
        this.webAuthorization = Objects.requireNonNull(webAuthorization);
        this.transactionAuthorization = Objects.requireNonNull(transactionAuthorization);
        this.register = Objects.requireNonNull(register);
        this.seal = Objects.requireNonNull(seal);
    }

    public boolean validateApprovedReview(
            UUID reviewId, EntityListSnapshotLedgerCoordinator.Head candidate) {
        if (reviewId == null || candidate == null || candidate.evidence() == null) {
            return false;
        }
        var principal = currentUserService.getCurrentWebUser()
                .filter(user -> Boolean.TRUE.equals(user.getIsActive())
                        && user.getId() != null);
        if (principal.isEmpty()) {
            return false;
        }
        UUID executor = principal.get().getId();
        var review = register.get(reviewId);
        if (review == null || !"APPROVED".equals(review.state())
                || review.reviewer() == null
                || executor.equals(review.requester())
                || executor.equals(review.reviewer())
                || !webAuthorization.mayInitialize(executor, review.key())) {
            return false;
        }
        // Re-read and re-authorize all review/head/candidate evidence inside
        // the seal transaction: preflight alone is NEVER sufficient.
        return seal.validateAndSealAuthorized(
                reviewId, executor, candidate, transactionAuthorization);
    }
}
