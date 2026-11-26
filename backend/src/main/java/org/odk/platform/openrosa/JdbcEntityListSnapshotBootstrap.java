package org.odk.platform.openrosa;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Opt-in first-snapshot bootstrap for a separately provisioned PostgreSQL
 * ledger. Not a Spring bean, controller, migration or production activation.
 *
 * The caller must supply a trusted authenticated actor and a project/form/list
 * authorization implementation. No revision is accepted from the client:
 * bootstrap always creates revision 1. The database unique keys arbitrate
 * competing initializations. No deletion or empty list is eligible.
 */
public final class JdbcEntityListSnapshotBootstrap {
    @FunctionalInterface
    public interface Authorization {
        boolean mayInitialize(UUID authenticatedActorId,
                              EntityListSnapshotLedgerCoordinator.Key key);
    }

    /**
     * Additional database-backed permission check on the SAME connection and
     * transaction as history/head inserts. Implementations must lock the
     * relevant membership/role rows and fail closed on revocation.
     */
    @FunctionalInterface
    public interface TransactionAuthorization {
        boolean mayInitialize(Connection connection, UUID authenticatedActorId,
                              EntityListSnapshotLedgerCoordinator.Key key) throws SQLException;
    }

    /**
     * Inserts an immutable successful-initialization audit record using the
     * same connection/transaction as the revision and current head. The
     * caller must provide a durable, append-only audit implementation.
     */
    @FunctionalInterface
    public interface TransactionAudit {
        void recordInitialized(Connection connection, UUID authenticatedActorId,
                EntityListSnapshotLedgerCoordinator.Key key,
                EntityListSnapshotEvidence.Snapshot evidence) throws SQLException;
    }

    private final DataSource dataSource;
    private final Authorization authorization;

    public JdbcEntityListSnapshotBootstrap(DataSource dataSource, Authorization authorization) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.authorization = Objects.requireNonNull(authorization);
    }

    public enum Outcome {
        INITIALIZED, DENIED, ALREADY_INITIALIZED, INVALID
    }

    public Outcome initialize(UUID authenticatedActorId,
            EntityListSnapshotLedgerCoordinator.Key key,
            Map<String, Object> metadata, byte[] bytes, String recordedMd5, Long rowCount) {
        return initializeInternal(authenticatedActorId, key, metadata, bytes,
                recordedMd5, rowCount, null, null);
    }

    /**
     * Opt-in stricter bootstrap. The callback runs inside the write
     * transaction, after the existing authenticated-web-user gate.
     * This is NOT active in production until a database-locking policy and
     * immutable actor audit are independently qualified.
     */
    public Outcome initializeWithTransactionAuthorization(
            UUID authenticatedActorId, EntityListSnapshotLedgerCoordinator.Key key,
            Map<String, Object> metadata, byte[] bytes, String recordedMd5,
            Long rowCount, TransactionAuthorization transactionalAuthorization) {
        Objects.requireNonNull(transactionalAuthorization);
        return initializeInternal(authenticatedActorId, key, metadata, bytes,
                recordedMd5, rowCount, transactionalAuthorization, null);
    }

    /**
     * Strict opt-in bootstrap contract: BOTH transaction-scoped authorization
     * and immutable success audit must complete before the snapshot commits.
     * Neither audit nor ledger data may remain if either operation fails.
     */
    public Outcome initializeWithTransactionAudit(
            UUID authenticatedActorId, EntityListSnapshotLedgerCoordinator.Key key,
            Map<String, Object> metadata, byte[] bytes, String recordedMd5,
            Long rowCount, TransactionAuthorization transactionalAuthorization,
            TransactionAudit transactionalAudit) {
        Objects.requireNonNull(transactionalAuthorization);
        Objects.requireNonNull(transactionalAudit);
        return initializeInternal(authenticatedActorId, key, metadata, bytes,
                recordedMd5, rowCount, transactionalAuthorization, transactionalAudit);
    }

    private Outcome initializeInternal(UUID authenticatedActorId,
            EntityListSnapshotLedgerCoordinator.Key key,
            Map<String, Object> metadata, byte[] bytes, String recordedMd5, Long rowCount,
            TransactionAuthorization transactionalAuthorization,
            TransactionAudit transactionalAudit) {
        if (authenticatedActorId == null || key == null
                || key.projectId() == null || key.formId() == null
                || !EntityListManifestPreflight.validTypedFilename(key.filename())
                || metadata == null) {
            return Outcome.INVALID;
        }
        if (!authorization.mayInitialize(authenticatedActorId, key)) {
            return Outcome.DENIED;
        }
        var snapshot = EntityListSnapshotEvidence.fromVerifiedBytes(
                key.projectId(), key.formId(), key.filename(), 1L,
                metadata, bytes, recordedMd5, rowCount);
        if (snapshot.isEmpty()) {
            return Outcome.INVALID;
        }
        var evidence = snapshot.get();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (transactionalAuthorization != null
                        && !transactionalAuthorization.mayInitialize(
                                connection, authenticatedActorId, key)) {
                    connection.rollback();
                    return Outcome.DENIED;
                }
                String history = "INSERT INTO entity_list_snapshot_history "
                        + "(project_id,form_id,filename,revision,media_type,md5,sha256,row_count,payload) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)";
                try (PreparedStatement insert = connection.prepareStatement(history)) {
                    insert.setObject(1, key.projectId());
                    insert.setObject(2, key.formId());
                    insert.setString(3, key.filename());
                    insert.setLong(4, 1L);
                    insert.setString(5, evidence.mediaType());
                    insert.setString(6, evidence.md5());
                    insert.setString(7, evidence.sha256());
                    insert.setLong(8, evidence.rowCount());
                    insert.setBytes(9, bytes);
                    insert.executeUpdate();
                }
                String head = "INSERT INTO entity_list_snapshot_heads "
                        + "(project_id,form_id,filename,revision,sha256) VALUES (?,?,?,?,?)";
                try (PreparedStatement insert = connection.prepareStatement(head)) {
                    insert.setObject(1, key.projectId());
                    insert.setObject(2, key.formId());
                    insert.setString(3, key.filename());
                    insert.setLong(4, 1L);
                    insert.setString(5, evidence.sha256());
                    insert.executeUpdate();
                }
                if (transactionalAudit != null) {
                    transactionalAudit.recordInitialized(
                            connection, authenticatedActorId, key, evidence);
                }
                connection.commit();
                return Outcome.INITIALIZED;
            } catch (SQLException | RuntimeException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    error.addSuppressed(rollback);
                }
                if (error instanceof SQLException sql
                        && "23505".equals(sql.getSQLState())) {
                    return Outcome.ALREADY_INITIALIZED;
                }
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Entity List snapshot initialization failed", error);
        }
    }
}
