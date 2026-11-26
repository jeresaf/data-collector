package org.odk.platform.openrosa;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Inactive ledger coordination contract. No production Store implementation
 * is registered or wired to application controllers.
 *
 * Store implementations MUST enforce compareAndAppend atomically with a
 * project/form/list-scoped lock and an immutable, durable snapshot archive.
 * A boolean from a non-atomic implementation is not sufficient for release.
 */
public final class EntityListSnapshotLedgerCoordinator {
    private EntityListSnapshotLedgerCoordinator() {
    }

    public record Key(UUID projectId, UUID formId, String filename) {
    }

    public record Head(EntityListSnapshotEvidence.Snapshot evidence, byte[] bytes) {
        public Head {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public interface Store {
        Optional<Head> currentHead(Key key);

        /**
         * Implementations must independently enforce the project/form/list
         * key against BOTH expected and proposed evidence, then atomically
         * compare exact expected revision and digest with their committed head
         * before appending immutable bytes/evidence. A mismatched scope or
         * stale head must never write anything. No auto-retries, silent
         * overwrites or deletes.
         */
        boolean compareAndAppend(Key key, Head expected, Head proposed);
    }

    public enum Outcome {
        UNCHANGED,
        APPENDED,
        REMOVAL_APPROVAL_REQUIRED,
        CONFLICT,
        INVALID_OR_NOT_INITIALIZED
    }

    public record Result(Outcome outcome, long proposedRevision, Set<UUID> missingIds) {
        public Result {
            missingIds = Set.copyOf(missingIds);
        }
    }

    public static Result propose(
            Store store, Key key, Map<String, Object> metadata,
            byte[] bytes, String recordedMd5, Long rowCount) {
        Result invalid = new Result(Outcome.INVALID_OR_NOT_INITIALIZED, 0, Set.of());
        if (store == null || key == null || key.projectId() == null
                || key.formId() == null || !EntityListManifestPreflight.validTypedFilename(key.filename())
                || metadata == null || bytes == null || recordedMd5 == null) {
            return invalid;
        }
        Optional<Head> current = store.currentHead(key);
        if (current.isEmpty()) {
            // First-revision initialization requires separate project ownership
            // and dataset-authority checks. This coordinator cannot bootstrap.
            return invalid;
        }
        Head head = current.get();
        EntityListSnapshotEvidence.Snapshot previous = head.evidence();
        if (previous == null || !key.projectId().equals(previous.projectId())
                || !key.formId().equals(previous.formId())
                || !key.filename().equals(previous.filename())
                || previous.revision() == Long.MAX_VALUE) {
            return invalid;
        }
        Optional<EntityListSnapshotEvidence.Snapshot> retryEvidence =
                EntityListSnapshotEvidence.fromVerifiedBytes(
                        key.projectId(), key.formId(), key.filename(),
                        previous.revision(), metadata, bytes, recordedMd5, rowCount);
        if (retryEvidence.isEmpty()) {
            return invalid;
        }
        if (retryEvidence.get().equals(previous)) {
            // Validate the previous bytes as well; never call an untrusted or
            // corrupted descriptor a successful idempotent retry.
            var checkedHead = EntityListSnapshotRevisionPolicy.assess(
                    previous, head.bytes(), previous, bytes);
            return checkedHead.status() == EntityListSnapshotRevisionPolicy.Status.UNCHANGED
                    ? new Result(Outcome.UNCHANGED, previous.revision(), Set.of()) : invalid;
        }
        Optional<EntityListSnapshotEvidence.Snapshot> proposedEvidence =
                EntityListSnapshotEvidence.fromVerifiedBytes(
                        key.projectId(), key.formId(), key.filename(),
                        previous.revision() + 1, metadata, bytes, recordedMd5, rowCount);
        if (proposedEvidence.isEmpty()) {
            return invalid;
        }
        Head proposed = new Head(proposedEvidence.get(), bytes);
        EntityListSnapshotRevisionPolicy.Assessment assessment =
                EntityListSnapshotRevisionPolicy.assess(
                        previous, head.bytes(), proposed.evidence(), proposed.bytes());
        if (assessment.status()
                == EntityListSnapshotRevisionPolicy.Status.REQUIRES_REMOVAL_APPROVAL) {
            return new Result(Outcome.REMOVAL_APPROVAL_REQUIRED,
                    proposed.evidence().revision(), assessment.missingIds());
        }
        if (assessment.status()
                != EntityListSnapshotRevisionPolicy.Status.READY_FOR_LEDGER_APPEND) {
            return invalid;
        }
        return store.compareAndAppend(key, head, proposed)
                ? new Result(Outcome.APPENDED, proposed.evidence().revision(), Set.of())
                : new Result(Outcome.CONFLICT, proposed.evidence().revision(), Set.of());
    }
}
