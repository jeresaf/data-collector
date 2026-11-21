package org.odk.platform.openrosa;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Opt-in transactional authorization for a first Entity List snapshot.
 * Mirrors the management rules used by WebFormAssignmentService, but checks
 * current active user/project/form and locks the actual rows in the SAME
 * JDBC transaction as bootstrap history and head inserts.
 *
 * There is no Spring bean or controller wiring. Production activation also
 * requires a database role model, immutable actor audit, and consistent lock
 * ordering for every privilege-revocation writer.
 */
public final class JdbcEntityListSnapshotPermissionGate
        implements JdbcEntityListSnapshotBootstrap.TransactionAuthorization {

    @Override
    public boolean mayInitialize(Connection connection, UUID actor,
            EntityListSnapshotLedgerCoordinator.Key key) throws SQLException {
        if (connection == null || connection.getAutoCommit()
                || actor == null || key == null || key.projectId() == null
                || key.formId() == null
                || !EntityListManifestPreflight.validTypedFilename(key.filename())) {
            return false;
        }
        if (!activeUserLocked(connection, actor)) {
            return false;
        }
        boolean ownsOrCreated;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_id, created_by_id FROM projects "
                        + "WHERE id=? AND is_active=TRUE FOR UPDATE")) {
            statement.setObject(1, key.projectId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return false;
                }
                ownsOrCreated = actor.equals(rows.getObject("owner_id", UUID.class))
                        || actor.equals(rows.getObject("created_by_id", UUID.class));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM xforms WHERE id=? AND project_id=? "
                        + "AND is_active=TRUE FOR UPDATE")) {
            statement.setObject(1, key.formId());
            statement.setObject(2, key.projectId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return false;
                }
            }
        }
        if (ownsOrCreated) {
            return true;
        }
        // Explicitly lock BOTH membership and role. Matching role grants
        // must not be resolved through stale session claims.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pm.id FROM project_members pm "
                        + "JOIN roles r ON r.id=pm.role_id "
                        + "WHERE pm.project_id=? AND pm.user_id=? "
                        + "AND pm.is_active=TRUE AND r.is_active=TRUE "
                        + "AND r.name IN ('PROJECT_ADMIN','PROJECT_MANAGER') "
                        + "FOR UPDATE OF pm,r")) {
            statement.setObject(1, key.projectId());
            statement.setObject(2, actor);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private boolean activeUserLocked(Connection connection, UUID actor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM users WHERE id=? AND is_active=TRUE FOR UPDATE")) {
            statement.setObject(1, actor);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }
}
