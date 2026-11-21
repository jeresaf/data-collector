package org.odk.platform.openrosa;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pure, inactive admission policy for a future transactionally persisted
 * Entity List snapshot ledger.
 *
 * <p>The trusted database layer must provide and lock the current head,
 * assign the proposed revision, atomically append immutable evidence and
 * enforce permissions. Caller-supplied heads or revisions are NOT trustworthy.
 * No storage, publication, approvals, endpoints or deletions occur here.</p>
 */
public final class EntityListSnapshotRevisionPolicy {
    private EntityListSnapshotRevisionPolicy() {
    }

    public enum Status {
        UNCHANGED,
        READY_FOR_LEDGER_APPEND,
        REQUIRES_REMOVAL_APPROVAL,
        CONFLICT_OR_UNTRUSTED
    }

    public record Assessment(Status status, long proposedRevision, Set<UUID> missingIds) {
        public Assessment {
            missingIds = Set.copyOf(missingIds);
        }
    }

    public static Assessment assess(
            EntityListSnapshotEvidence.Snapshot currentHead,
            byte[] currentBytes,
            EntityListSnapshotEvidence.Snapshot proposed,
            byte[] proposedBytes) {
        Assessment denied = new Assessment(Status.CONFLICT_OR_UNTRUSTED, 0, Set.of());
        if (!validEvidence(currentHead, currentBytes)
                || !validEvidence(proposed, proposedBytes)
                || !currentHead.projectId().equals(proposed.projectId())
                || !currentHead.formId().equals(proposed.formId())
                || !currentHead.filename().equals(proposed.filename())
                || !currentHead.mediaType().equals(proposed.mediaType())) {
            return denied;
        }
        // Retry of precisely the current committed snapshot is idempotent.
        if (currentHead.equals(proposed)) {
            return new Assessment(Status.UNCHANGED, currentHead.revision(), Set.of());
        }
        if (currentHead.revision() == Long.MAX_VALUE
                || proposed.revision() != currentHead.revision() + 1) {
            return denied;
        }
        Optional<Set<UUID>> missing = EntityListSnapshotEvidence.reviewMissingIds(
                currentHead, currentBytes, proposed, proposedBytes);
        if (missing.isEmpty()) {
            return denied;
        }
        return new Assessment(missing.get().isEmpty()
                ? Status.READY_FOR_LEDGER_APPEND : Status.REQUIRES_REMOVAL_APPROVAL,
                proposed.revision(), missing.get());
    }

    private static boolean validEvidence(EntityListSnapshotEvidence.Snapshot snapshot, byte[] bytes) {
        if (snapshot == null || snapshot.projectId() == null || snapshot.formId() == null
                || snapshot.filename() == null || snapshot.mediaType() == null
                || snapshot.md5() == null || snapshot.sha256() == null) {
            return false;
        }
        return EntityListSnapshotEvidence.fromVerifiedBytes(
                snapshot.projectId(), snapshot.formId(), snapshot.filename(),
                snapshot.revision(),
                Map.of(EntityListManifestPreflight.METADATA_KEY, snapshot.mediaType()),
                bytes, snapshot.md5(), snapshot.rowCount())
                .filter(snapshot::equals).isPresent();
    }
}
