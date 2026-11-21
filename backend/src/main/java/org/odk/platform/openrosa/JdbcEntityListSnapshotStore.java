package org.odk.platform.openrosa;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

/**
 * Opt-in JDBC snapshot store. This class is NOT registered as a Spring bean;
 * no schema is created or migrated by this class. Provision the two tables
 * through a separately approved migration and enforce authorization before
 * calling it. It provides no trusted first-revision bootstrap or deletion
 * approval workflow.
 *
 * Required tables:
 * entity_list_snapshot_heads(project_id uuid, form_id uuid, filename text,
 *   revision bigint, sha256 text), PK(project_id,form_id,filename);
 * entity_list_snapshot_history(project_id uuid,form_id uuid,filename text,
 *   revision bigint,media_type text,md5 text,sha256 text,row_count bigint,
 *   payload bytea), PK(project_id,form_id,filename,revision).
 */
public final class JdbcEntityListSnapshotStore
        implements EntityListSnapshotLedgerCoordinator.Store {
    /** Writes the append event in the same transaction as history and head. */
    @FunctionalInterface
    public interface TransactionAppendAudit {
        void recordAppended(Connection connection, java.util.UUID actor,
                EntityListSnapshotLedgerCoordinator.Key key,
                EntityListSnapshotEvidence.Snapshot evidence) throws SQLException;
    }

    private final DataSource dataSource;

    public JdbcEntityListSnapshotStore(DataSource dataSource) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource);
    }

    @Override
    public Optional<EntityListSnapshotLedgerCoordinator.Head> currentHead(
            EntityListSnapshotLedgerCoordinator.Key key) {
        if (!validKey(key)) {
            return Optional.empty();
        }
        String sql = "SELECT h.revision, r.media_type, r.md5, r.sha256, r.row_count, r.payload "
                + "FROM entity_list_snapshot_heads h JOIN entity_list_snapshot_history r "
                + "ON r.project_id=h.project_id AND r.form_id=h.form_id "
                + "AND r.filename=h.filename AND r.revision=h.revision "
                + "AND r.sha256=h.sha256 "
                + "WHERE h.project_id=? AND h.form_id=? AND h.filename=?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindKey(statement, key, 1);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                var evidence = new EntityListSnapshotEvidence.Snapshot(
                        key.projectId(), key.formId(), key.filename(),
                        row.getString("media_type"), row.getLong("revision"),
                        row.getString("md5"), row.getString("sha256"),
                        row.getLong("row_count"));
                byte[] bytes = row.getBytes("payload");
                if (!validEvidence(key, evidence, bytes)) {
                    // Corrupt history must never be interpreted as an empty head
                    // and silently reinitialized.
                    throw new IllegalStateException("Invalid stored Entity List snapshot evidence");
                }
                return Optional.of(new EntityListSnapshotLedgerCoordinator.Head(evidence, bytes));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read Entity List snapshot head", e);
        }
    }

    @Override
    public boolean compareAndAppend(
            EntityListSnapshotLedgerCoordinator.Key key,
            EntityListSnapshotLedgerCoordinator.Head expected,
            EntityListSnapshotLedgerCoordinator.Head proposed) {
        return appendInternal(key, expected, proposed, null, null, null);
    }

    /**
     * Strict opt-in append with permission recheck and immutable audit in the
     * exact database transaction. This method is NOT called by production
     * controllers; the basic Store interface remains unaudited/inactive.
     */
    public boolean compareAndAppendAudited(
            EntityListSnapshotLedgerCoordinator.Key key,
            EntityListSnapshotLedgerCoordinator.Head expected,
            EntityListSnapshotLedgerCoordinator.Head proposed,
            java.util.UUID authenticatedActorId,
            JdbcEntityListSnapshotBootstrap.TransactionAuthorization authorization,
            TransactionAppendAudit audit) {
        java.util.Objects.requireNonNull(authorization);
        java.util.Objects.requireNonNull(audit);
        if (authenticatedActorId == null) {
            return false;
        }
        return appendInternal(key, expected, proposed,
                authenticatedActorId, authorization, audit);
    }

    private boolean appendInternal(
            EntityListSnapshotLedgerCoordinator.Key key,
            EntityListSnapshotLedgerCoordinator.Head expected,
            EntityListSnapshotLedgerCoordinator.Head proposed,
            java.util.UUID authenticatedActorId,
            JdbcEntityListSnapshotBootstrap.TransactionAuthorization authorization,
            TransactionAppendAudit audit) {
        if (!validKey(key) || expected == null || proposed == null
                || !validEvidence(key, expected.evidence(), expected.bytes())
                || !validEvidence(key, proposed.evidence(), proposed.bytes())
                || expected.evidence().revision() == Long.MAX_VALUE
                || proposed.evidence().revision() != expected.evidence().revision() + 1
                || !expected.evidence().mediaType().equals(proposed.evidence().mediaType())) {
            return false;
        }
        // Fail closed even for callers bypassing the coordinator: a removal
        // cannot be appended through the opt-in store before an independent
        // approved-deletion transaction/service exists.
        var review = EntityListSnapshotRevisionPolicy.assess(
                expected.evidence(), expected.bytes(),
                proposed.evidence(), proposed.bytes());
        if (review.status() != EntityListSnapshotRevisionPolicy.Status.READY_FOR_LEDGER_APPEND) {
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (authorization != null
                        && !authorization.mayInitialize(
                                connection, authenticatedActorId, key)) {
                    connection.rollback();
                    return false;
                }
                String cas = "UPDATE entity_list_snapshot_heads SET revision=?, sha256=? "
                        + "WHERE project_id=? AND form_id=? AND filename=? "
                        + "AND revision=? AND sha256=?";
                int changed;
                try (PreparedStatement statement = connection.prepareStatement(cas)) {
                    statement.setLong(1, proposed.evidence().revision());
                    statement.setString(2, proposed.evidence().sha256());
                    bindKey(statement, key, 3);
                    statement.setLong(6, expected.evidence().revision());
                    statement.setString(7, expected.evidence().sha256());
                    changed = statement.executeUpdate();
                }
                if (changed != 1) {
                    connection.rollback();
                    return false;
                }
                String append = "INSERT INTO entity_list_snapshot_history "
                        + "(project_id,form_id,filename,revision,media_type,md5,sha256,row_count,payload) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)";
                try (PreparedStatement statement = connection.prepareStatement(append)) {
                    bindKey(statement, key, 1);
                    var evidence = proposed.evidence();
                    statement.setLong(4, evidence.revision());
                    statement.setString(5, evidence.mediaType());
                    statement.setString(6, evidence.md5());
                    statement.setString(7, evidence.sha256());
                    statement.setLong(8, evidence.rowCount());
                    statement.setBytes(9, proposed.bytes());
                    statement.executeUpdate();
                }
                if (audit != null) {
                    audit.recordAppended(
                            connection, authenticatedActorId, key, proposed.evidence());
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Atomic Entity List snapshot append failed", e);
        }
    }

    private static boolean validKey(EntityListSnapshotLedgerCoordinator.Key key) {
        return key != null && key.projectId() != null && key.formId() != null
                && EntityListManifestPreflight.validTypedFilename(key.filename());
    }

    private static boolean validEvidence(EntityListSnapshotLedgerCoordinator.Key key,
            EntityListSnapshotEvidence.Snapshot snapshot, byte[] bytes) {
        if (snapshot == null || bytes == null
                || !key.projectId().equals(snapshot.projectId())
                || !key.formId().equals(snapshot.formId())
                || !key.filename().equals(snapshot.filename())
                || snapshot.mediaType() == null || snapshot.md5() == null
                || snapshot.sha256() == null) {
            return false;
        }
        var verified = EntityListSnapshotEvidence.fromVerifiedBytes(
                snapshot.projectId(), snapshot.formId(), snapshot.filename(),
                snapshot.revision(),
                Map.of(EntityListManifestPreflight.METADATA_KEY, snapshot.mediaType()),
                bytes, snapshot.md5(), snapshot.rowCount());
        return verified.filter(snapshot::equals).isPresent();
    }

    private static void bindKey(PreparedStatement statement,
            EntityListSnapshotLedgerCoordinator.Key key, int offset) throws SQLException {
        statement.setObject(offset, key.projectId());
        statement.setObject(offset + 1, key.formId());
        statement.setString(offset + 2, key.filename());
    }
}
