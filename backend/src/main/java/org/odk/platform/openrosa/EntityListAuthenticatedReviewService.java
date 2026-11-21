package org.odk.platform.openrosa;

import org.odk.platform.security.CurrentUserService;

import java.util.Objects;
import java.util.UUID;

/**
 * Inactive review-decision facade. Reviewer identity is always taken from
 * the current authenticated web principal, never from request parameters.
 * Permission is checked both before AND inside the review-row transaction.
 *
 * Does not register a Spring endpoint/bean or approve Entity List deletion.
 */
public final class EntityListAuthenticatedReviewService {
    private final CurrentUserService currentUserService;
    private final EntityListSnapshotWebAuthorization webAuthorization;
    private final JdbcEntityListSnapshotPermissionGate transactionAuthorization;
    private final JdbcEntityListRemovalReviewRegister register;

    public EntityListAuthenticatedReviewService(
            CurrentUserService currentUserService,
            EntityListSnapshotWebAuthorization webAuthorization,
            JdbcEntityListSnapshotPermissionGate transactionAuthorization,
            JdbcEntityListRemovalReviewRegister register) {
        this.currentUserService = Objects.requireNonNull(currentUserService);
        this.webAuthorization = Objects.requireNonNull(webAuthorization);
        this.transactionAuthorization = Objects.requireNonNull(transactionAuthorization);
        this.register = Objects.requireNonNull(register);
    }

    public boolean decide(UUID reviewId, JdbcEntityListRemovalReviewRegister.Decision decision) {
        if (reviewId == null || decision == null) {
            return false;
        }
        var reviewer = currentUserService.getCurrentWebUser()
                .filter(user -> Boolean.TRUE.equals(user.getIsActive()))
                .map(user -> user.getId());
        if (reviewer.isEmpty() || reviewer.get() == null) {
            return false;
        }
        var request = register.get(reviewId);
        if (request == null || !"PENDING".equals(request.state())
                || reviewer.get().equals(request.requester())
                || !webAuthorization.mayInitialize(reviewer.get(), request.key())) {
            return false;
        }
        // Mandatory in-transaction recheck after SELECT ... FOR UPDATE.
        // The register itself cannot trust the preliminary service check.
        return register.decideAuthorized(reviewId, reviewer.get(), decision,
                transactionAuthorization);
    }
}
