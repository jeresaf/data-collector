package org.odk.platform.openrosa;

import org.odk.platform.security.CurrentUserService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Unregistered test-schema-only removal-role administration proof.
 * Principal is obtained from the CURRENT active web session, never an
 * HTTP-supplied UUID. The provisioned administrator record and the existing
 * project/form manager gate are both locked and checked in the SAME
 * transaction as the state change and immutable transition audit.
 *
 * No endpoint, migration, grant provisioning or removal is activated.
 */
public final class EntityListRemovalGrantAdministration {
    public enum Change { GRANT, REVOKE }

    private final DataSource dataSource;
    private final CurrentUserService currentUserService;
    private final JdbcEntityListSnapshotBootstrap.TransactionAuthorization managerGate;

    public EntityListRemovalGrantAdministration(DataSource dataSource,
            CurrentUserService currentUserService,
            JdbcEntityListSnapshotBootstrap.TransactionAuthorization managerGate) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.currentUserService = Objects.requireNonNull(currentUserService);
        this.managerGate = Objects.requireNonNull(managerGate);
    }

    public boolean change(EntityListSnapshotLedgerCoordinator.Key key,
            UUID subject, JdbcEntityListRemovalRoleGate.Role role, Change action) {
        if (key == null || subject == null || role == null || action == null) {
            return false;
        }
        var principal = currentUserService.getCurrentWebUser();
        if (principal.isEmpty() || principal.get().getId() == null
                || !Boolean.TRUE.equals(principal.get().getIsActive())) {
            return false;
        }
        UUID administrator = principal.get().getId();
        // No administrator may issue their own grants, including via REVOKE.
        if (administrator.equals(subject)) {
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Normal management authorization (active principal, project,
                // form and current membership) is rechecked within the TX.
                if (!managerGate.mayInitialize(connection, administrator, key)) {
                    connection.rollback();
                    return false;
                }
                // Separate, explicitly provisioned grant administrator,
                // never inferred from project manager status.
                try (PreparedStatement permission = connection.prepareStatement(
                        "SELECT administrator_id FROM entity_list_removal_grant_administrators "
                                + "WHERE project_id=? AND administrator_id=? "
                                + "AND is_active=TRUE FOR UPDATE")) {
                    permission.setObject(1, key.projectId());
                    permission.setObject(2, administrator);
                    try (ResultSet row = permission.executeQuery()) {
                        if (!row.next()) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                // Existing grants are locked; the unique key serializes
                // concurrent INSERT attempts for a new delegation.
                boolean exists;
                boolean active = false;
                try (PreparedStatement read = connection.prepareStatement(
                        "SELECT is_active FROM entity_list_removal_role_grants "
                                + "WHERE project_id=? AND user_id=? "
                                + "AND role_name=? FOR UPDATE")) {
                    read.setObject(1, key.projectId());
                    read.setObject(2, subject);
                    read.setString(3, role.name());
                    try (ResultSet row = read.executeQuery()) {
                        exists = row.next();
                        if (exists) {
                            active = row.getBoolean("is_active");
                        }
                    }
                }
                if ((action == Change.GRANT && active)
                        || (action == Change.REVOKE && !active)) {
                    connection.rollback();
                    return false;
                }
                int updated;
                if (action == Change.GRANT && !exists) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO entity_list_removal_role_grants "
                                    + "(project_id,user_id,role_name,is_active) "
                                    + "VALUES (?,?,?,TRUE) ON CONFLICT DO NOTHING")) {
                        insert.setObject(1, key.projectId());
                        insert.setObject(2, subject);
                        insert.setString(3, role.name());
                        updated = insert.executeUpdate();
                    }
                } else {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE entity_list_removal_role_grants SET is_active=? "
                                    + "WHERE project_id=? AND user_id=? AND role_name=? "
                                    + "AND is_active=?")) {
                        update.setBoolean(1, action == Change.GRANT);
                        update.setObject(2, key.projectId());
                        update.setObject(3, subject);
                        update.setString(4, role.name());
                        update.setBoolean(5, action == Change.REVOKE);
                        updated = update.executeUpdate();
                    }
                }
                if (updated != 1) {
                    connection.rollback();
                    return false;
                }
                // Audit and state transition commit atomically; audit failure
                // rolls the grant mutation back. Audit is append-only.
                try (PreparedStatement audit = connection.prepareStatement(
                        "INSERT INTO entity_list_removal_grant_audit "
                                + "(event_id,project_id,administrator_id,subject_id,"
                                + "role_name,action) VALUES (?,?,?,?,?,?)")) {
                    audit.setObject(1, UUID.randomUUID());
                    audit.setObject(2, key.projectId());
                    audit.setObject(3, administrator);
                    audit.setObject(4, subject);
                    audit.setString(5, role.name());
                    audit.setString(6, action.name());
                    if (audit.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot transition audited Entity List removal grant", error);
        }
    }
}
