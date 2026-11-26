package org.odk.platform.openrosa;

import org.odk.platform.security.CurrentUserService;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The only intended revision-publication facade for future application wiring.
 * Unregistered and inactive: there is no controller, Spring bean or migration.
 *
 * Derives the actor from the server's current web security context, requires
 * the active project/form manager policy, then routes ALL revision writes
 * through the JDBC store's transaction-scoped permission AND audit callbacks.
 * The generic coordinator's Store interface is wrapped; it is never given
 * the raw, unaudited JDBC store by this facade.
 *
 * Production activation additionally needs a reviewed migration, mandatory
 * service wiring, coordinated revocation locking and denial-event audit.
 */
public final class EntityListAuditedRevisionService {
    private final JdbcEntityListSnapshotStore store;
    private final CurrentUserService currentUsers;
    private final EntityListSnapshotWebAuthorization webAuthorization;
    private final JdbcEntityListSnapshotPermissionGate transactionAuthorization;
    private final JdbcEntityListSnapshotAuditWriter audit;

    public EntityListAuditedRevisionService(
            JdbcEntityListSnapshotStore store,
            CurrentUserService currentUsers,
            EntityListSnapshotWebAuthorization webAuthorization,
            JdbcEntityListSnapshotPermissionGate transactionAuthorization,
            JdbcEntityListSnapshotAuditWriter audit) {
        this.store = Objects.requireNonNull(store);
        this.currentUsers = Objects.requireNonNull(currentUsers);
        this.webAuthorization = Objects.requireNonNull(webAuthorization);
        this.transactionAuthorization = Objects.requireNonNull(transactionAuthorization);
        this.audit = Objects.requireNonNull(audit);
    }

    public EntityListSnapshotLedgerCoordinator.Result propose(
            EntityListSnapshotLedgerCoordinator.Key key,
            Map<String, Object> metadata, byte[] bytes, String recordedMd5, Long rowCount) {
        var rejected = new EntityListSnapshotLedgerCoordinator.Result(
                EntityListSnapshotLedgerCoordinator.Outcome.INVALID_OR_NOT_INITIALIZED,
                0L, Set.of());
        if (key == null || key.projectId() == null || key.formId() == null
                || !EntityListManifestPreflight.validTypedFilename(key.filename())) {
            return rejected;
        }
        Optional<UUID> actor = currentUsers.getCurrentWebUser()
                .filter(user -> Boolean.TRUE.equals(user.getIsActive()))
                .map(user -> user.getId());
        if (actor.isEmpty() || !webAuthorization.mayInitialize(actor.get(), key)) {
            return rejected;
        }
        // Bind the authenticated actor to this one proposal. The coordinator
        // may classify retries, removals and races, but may write only via
        // compareAndAppendAudited, never the old plain JDBC append method.
        EntityListSnapshotLedgerCoordinator.Store audited = new EntityListSnapshotLedgerCoordinator.Store() {
            @Override
            public Optional<EntityListSnapshotLedgerCoordinator.Head> currentHead(
                    EntityListSnapshotLedgerCoordinator.Key scope) {
                return store.currentHead(scope);
            }

            @Override
            public boolean compareAndAppend(EntityListSnapshotLedgerCoordinator.Key scope,
                    EntityListSnapshotLedgerCoordinator.Head expected,
                    EntityListSnapshotLedgerCoordinator.Head proposed) {
                return store.compareAndAppendAudited(scope, expected, proposed,
                        actor.get(), transactionAuthorization, audit);
            }
        };
        return EntityListSnapshotLedgerCoordinator.propose(
                audited, key, metadata, bytes, recordedMd5, rowCount);
    }
}
