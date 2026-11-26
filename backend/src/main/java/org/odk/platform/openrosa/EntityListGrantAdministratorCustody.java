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
 * Inactive test-only appointment/revocation of grant administrators.
 * A separately bootstrapped CUSTODIAN (not a grant administrator) must be
 * authenticated, active and currently authorized to manage the project/form.
 * Custodians cannot appoint/revoke themselves. Administrator state transitions
 * and append-only evidence commit in a single locked JDBC transaction.
 *
 * Custodian bootstrap/recovery and production operation remain UNIMPLEMENTED.
 */
public final class EntityListGrantAdministratorCustody {
    public enum Change { APPOINT, REVOKE }

    private final DataSource dataSource;
    private final CurrentUserService currentUserService;
    private final JdbcEntityListSnapshotBootstrap.TransactionAuthorization managerGate;

    public EntityListGrantAdministratorCustody(DataSource dataSource,
            CurrentUserService currentUserService,
            JdbcEntityListSnapshotBootstrap.TransactionAuthorization managerGate) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.currentUserService = Objects.requireNonNull(currentUserService);
        this.managerGate = Objects.requireNonNull(managerGate);
    }

    public boolean change(EntityListSnapshotLedgerCoordinator.Key key,
            UUID subject, Change change) {
        if (key == null || key.projectId() == null || subject == null || change == null) {
            return false;
        }
        var webPrincipal = currentUserService.getCurrentWebUser();
        if (webPrincipal.isEmpty() || webPrincipal.get().getId() == null
                || !Boolean.TRUE.equals(webPrincipal.get().getIsActive())) {
            return false;
        }
        UUID custodian = webPrincipal.get().getId();
        if (custodian.equals(subject)) {
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!managerGate.mayInitialize(connection, custodian, key)) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement permission = connection.prepareStatement(
                        "SELECT custodian_id FROM entity_list_grant_custodians "
                                + "WHERE project_id=? AND custodian_id=? "
                                + "AND is_active=TRUE FOR UPDATE")) {
                    permission.setObject(1, key.projectId());
                    permission.setObject(2, custodian);
                    try (ResultSet row = permission.executeQuery()) {
                        if (!row.next()) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                boolean exists;
                boolean currentlyActive = false;
                try (PreparedStatement read = connection.prepareStatement(
                        "SELECT is_active FROM entity_list_removal_grant_administrators "
                                + "WHERE project_id=? AND administrator_id=? FOR UPDATE")) {
                    read.setObject(1, key.projectId());
                    read.setObject(2, subject);
                    try (ResultSet row = read.executeQuery()) {
                        exists = row.next();
                        if (exists) {
                            currentlyActive = row.getBoolean(1);
                        }
                    }
                }
                if ((change == Change.APPOINT && currentlyActive)
                        || (change == Change.REVOKE && !currentlyActive)) {
                    connection.rollback();
                    return false;
                }
                int updated;
                if (change == Change.APPOINT && !exists) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO entity_list_removal_grant_administrators "
                                    + "(project_id,administrator_id,is_active) "
                                    + "VALUES (?,?,TRUE) ON CONFLICT DO NOTHING")) {
                        insert.setObject(1, key.projectId());
                        insert.setObject(2, subject);
                        updated = insert.executeUpdate();
                    }
                } else {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE entity_list_removal_grant_administrators "
                                    + "SET is_active=? WHERE project_id=? AND administrator_id=? "
                                    + "AND is_active=?")) {
                        update.setBoolean(1, change == Change.APPOINT);
                        update.setObject(2, key.projectId());
                        update.setObject(3, subject);
                        update.setBoolean(4, change == Change.REVOKE);
                        updated = update.executeUpdate();
                    }
                }
                if (updated != 1) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement evidence = connection.prepareStatement(
                        "INSERT INTO entity_list_grant_administrator_audit "
                                + "(event_id,project_id,custodian_id,administrator_id,action) "
                                + "VALUES (?,?,?,?,?)")) {
                    evidence.setObject(1, UUID.randomUUID());
                    evidence.setObject(2, key.projectId());
                    evidence.setObject(3, custodian);
                    evidence.setObject(4, subject);
                    evidence.setString(5, change.name());
                    if (evidence.executeUpdate() != 1) {
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
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot change Entity List grant-administrator custody", e);
        }
    }
}
