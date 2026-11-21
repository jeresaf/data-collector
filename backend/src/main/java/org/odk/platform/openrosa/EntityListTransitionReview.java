package org.odk.platform.openrosa;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Non-authoritative, read-only admission review for proposed Entity List
 * transitions. A positive review is NOT permission to publish or delete.
 *
 * <p>Any missing ID must be explicitly approved by a separate durable service.
 * This class never produces a deletion instruction or changes application state.</p>
 */
public final class EntityListTransitionReview {
    private EntityListTransitionReview() {
    }

    public enum Finding {
        NO_REMOVALS_OBSERVED,
        REMOVAL_REVIEW_REQUIRED,
        BULK_REMOVAL_REVIEW_REQUIRED
    }

    public record Review(
            EntityListSnapshotEvidence.Snapshot previous,
            EntityListSnapshotEvidence.Snapshot next,
            Finding finding,
            Set<UUID> missingIds,
            long previousRowCount,
            long nextRowCount) {
        public Review {
            missingIds = Set.copyOf(missingIds);
        }
    }

    /**
     * A bulk-removal warning is advisory only: all removals require review.
     * The threshold is conservative and cannot be treated as an approval cap.
     */
    public static Optional<Review> inspect(
            EntityListSnapshotEvidence.Snapshot previous, byte[] previousBytes,
            EntityListSnapshotEvidence.Snapshot next, byte[] nextBytes) {
        Optional<Set<UUID>> missing = EntityListSnapshotEvidence.reviewMissingIds(
                previous, previousBytes, next, nextBytes);
        if (missing.isEmpty()) {
            return Optional.empty();
        }
        Set<UUID> ids = missing.get();
        Finding finding;
        if (ids.isEmpty()) {
            finding = Finding.NO_REMOVALS_OBSERVED;
        } else if (ids.size() >= 10
                || ids.size() * 4L >= previous.rowCount()) {
            finding = Finding.BULK_REMOVAL_REVIEW_REQUIRED;
        } else {
            finding = Finding.REMOVAL_REVIEW_REQUIRED;
        }
        return Optional.of(new Review(previous, next, finding, ids,
                previous.rowCount(), next.rowCount()));
    }
}
