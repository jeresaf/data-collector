package org.odk.platform.openrosa;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Inactive transaction-scoped separation-of-duties gate for Entity List
 * removal review and approval validation.
 *
 * A PROJECT_ADMIN/MANAGER grant alone is NEVER a removal role. An explicit,
 * current, active project-scoped REVIEWER or EXECUTOR delegation is also
 * required. Both manager authority and delegation are rechecked on the
 * same connection while the caller holds its review/head locks.
 *
 * No migration, endpoint, bean or actual removal authority is registered.
 */
public final class JdbcEntityListRemovalRoleGate
        implements JdbcEntityListSnapshotBootstrap.TransactionAuthorization {

    public enum Role { REVIEWER, EXECUTOR }

    private final JdbcEntityListSnapshotBootstrap.TransactionAuthorization managerGate;
    private final Role role;

    public JdbcEntityListRemovalRoleGate(
            JdbcEntityListSnapshotBootstrap.TransactionAuthorization managerGate,
            Role role) {
        this.managerGate = Objects.requireNonNull(managerGate);
        this.role = Objects.requireNonNull(role);
    }

    public Role role() {
        return role;
    }

    @Override
    public boolean mayInitialize(Connection connection, UUID actor,
            EntityListSnapshotLedgerCoordinator.Key key) throws SQLException {
        if (connection == null || connection.getAutoCommit()
                || actor == null || key == null || key.projectId() == null
                || key.formId() == null
                || !EntityListManifestPreflight.validTypedFilename(key.filename())) {
            return false;
        }
        // Lock and validate current web manager privilege using the existing
        // transaction gate, including active user, project, form and membership.
        if (!managerGate.mayInitialize(connection, actor, key)) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT user_id FROM entity_list_removal_role_grants "
                        + "WHERE project_id=? AND user_id=? AND role_name=? "
                        + "AND is_active=TRUE FOR UPDATE")) {
            statement.setObject(1, key.projectId());
            statement.setObject(2, actor);
            statement.setString(3, role.name());
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }
}
