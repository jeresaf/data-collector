package org.odk.platform.openrosa;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Inactive, immutable evidence envelope for a proposed Entity List removal.
 *
 * Preparing a request does NOT authorize, approve, publish, tombstone, or
 * delete an Entity. A future durable service must lock the authoritative
 * current head, persist this evidence and require independent review.
 */
public final class EntityListRemovalReviewRequest {
    private EntityListRemovalReviewRequest() {
    }

    public record Request(
            UUID requestedBy,
            String justification,
            EntityListSnapshotEvidence.Snapshot expectedHead,
            EntityListSnapshotEvidence.Snapshot candidate,
            Set<UUID> missingIds) {
        public Request {
            if (requestedBy == null || justification == null
                    || justification.isBlank() || justification.length() > 2000
                    || expectedHead == null || candidate == null
                    || missingIds == null || missingIds.isEmpty()) {
                throw new IllegalArgumentException("Incomplete removal review request");
            }
            justification = justification.trim();
            missingIds = Set.copyOf(missingIds);
        }
    }

    /**
     * Produces evidence for independent review only after verifying BOTH
     * snapshots against their bytes and comparing the complete ID sets.
     * The actor and reason are caller-provided claims, not authenticated
     * authority; an enclosing service must bind them to the server principal.
     */
    public static Optional<Request> prepare(
            UUID requestedBy, String justification,
            EntityListSnapshotLedgerCoordinator.Head trustedHead,
            EntityListSnapshotLedgerCoordinator.Head candidate) {
        if (requestedBy == null || justification == null
                || justification.isBlank() || justification.length() > 2000
                || trustedHead == null || candidate == null
                || trustedHead.evidence() == null || candidate.evidence() == null) {
            return Optional.empty();
        }
        var assessment = EntityListSnapshotRevisionPolicy.assess(
                trustedHead.evidence(), trustedHead.bytes(),
                candidate.evidence(), candidate.bytes());
        if (assessment.status()
                != EntityListSnapshotRevisionPolicy.Status.REQUIRES_REMOVAL_APPROVAL
                || assessment.proposedRevision() != candidate.evidence().revision()
                || assessment.missingIds().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Request(requestedBy, justification,
                trustedHead.evidence(), candidate.evidence(),
                assessment.missingIds()));
    }

    /**
     * A stale head, changed candidate digest, changed actor, or changed
     * missing-ID set makes a previous review request unusable. This still
     * does not approve or execute a removal.
     */
    public static boolean matchesForReview(
            Request request, UUID requester,
            EntityListSnapshotLedgerCoordinator.Head currentHead,
            EntityListSnapshotLedgerCoordinator.Head candidate) {
        if (request == null || requester == null
                || !request.requestedBy().equals(requester)) {
            return false;
        }
        return prepare(requester, request.justification(), currentHead, candidate)
                .filter(recomputed -> recomputed.equals(request))
                .isPresent();
    }
}
