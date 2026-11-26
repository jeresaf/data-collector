package org.odk.platform.openrosa;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Opt-in SQL writer for successful first-revision audit evidence.
 * Requires an approved append-only audit table and a caller-supplied
 * authenticated web identity. No Spring registration or migration.
 *
 * This records only committed INITIALIZED decisions. DENIED/INVALID and
 * rollback attempts need a separate non-transactional security event log.
 */
public final class JdbcEntityListSnapshotAuditWriter
        implements JdbcEntityListSnapshotBootstrap.TransactionAudit,
        JdbcEntityListSnapshotStore.TransactionAppendAudit {
    @Override
    public void recordInitialized(Connection connection, UUID authenticatedActorId,
            EntityListSnapshotLedgerCoordinator.Key key,
            EntityListSnapshotEvidence.Snapshot evidence) throws SQLException {
        record(connection, authenticatedActorId, key, evidence, "INITIALIZED", true);
    }

    @Override
    public void recordAppended(Connection connection, UUID authenticatedActorId,
            EntityListSnapshotLedgerCoordinator.Key key,
            EntityListSnapshotEvidence.Snapshot evidence) throws SQLException {
        record(connection, authenticatedActorId, key, evidence, "APPENDED", false);
    }

    private void record(Connection connection, UUID authenticatedActorId,
            EntityListSnapshotLedgerCoordinator.Key key,
            EntityListSnapshotEvidence.Snapshot evidence, String event, boolean first)
            throws SQLException {
        if (connection == null || connection.getAutoCommit()
                || authenticatedActorId == null || key == null || evidence == null
                || !key.projectId().equals(evidence.projectId())
                || !key.formId().equals(evidence.formId())
                || !key.filename().equals(evidence.filename())
                || evidence.revision() < (first ? 1L : 2L)
                || (first && evidence.revision() != 1L)) {
            throw new SQLException("Invalid transaction-scoped initialization audit");
        }
        String sql = "INSERT INTO entity_list_snapshot_audit "
                + "(actor_id,project_id,form_id,filename,revision,sha256,event_type) "
                + "VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setObject(1, authenticatedActorId);
            insert.setObject(2, key.projectId());
            insert.setObject(3, key.formId());
            insert.setString(4, key.filename());
            insert.setLong(5, evidence.revision());
            insert.setString(6, evidence.sha256());
            insert.setString(7, event);
            if (insert.executeUpdate() != 1) {
                throw new SQLException("Initialization audit insert did not write exactly one row");
            }
        }
    }
}
