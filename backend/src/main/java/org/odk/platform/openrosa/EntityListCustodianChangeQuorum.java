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
 * Inactive, test-schema-only independent custody-change attestation.
 *
 * Proposer and TWO different witnesses must be current active web users,
 * existing project/form managers, and separately provisioned active
 * custodians. A quorum marks only an ENDORSED proposal. This service NEVER
 * appoints/revokes a custodian or provisions the first trusted custodian.
 */
public final class EntityListCustodianChangeQuorum {
    public enum Action { APPOINT, REVOKE }

    private final DataSource dataSource;
    private final CurrentUserService currentUserService;
    private final JdbcEntityListSnapshotBootstrap.TransactionAuthorization managerGate;

    public EntityListCustodianChangeQuorum(DataSource dataSource,
            CurrentUserService currentUserService,
            JdbcEntityListSnapshotBootstrap.TransactionAuthorization managerGate) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.currentUserService = Objects.requireNonNull(currentUserService);
        this.managerGate = Objects.requireNonNull(managerGate);
    }

    private UUID activePrincipal() {
        return currentUserService.getCurrentWebUser()
                .filter(user -> Boolean.TRUE.equals(user.getIsActive()))
                .map(user -> user.getId()).orElse(null);
    }

    private boolean authorizedCustodian(Connection connection, UUID actor,
            EntityListSnapshotLedgerCoordinator.Key key) throws SQLException {
        if (!managerGate.mayInitialize(connection, actor, key)) {
            return false;
        }
        try (PreparedStatement row = connection.prepareStatement(
                "SELECT custodian_id FROM entity_list_grant_custodians "
                        + "WHERE project_id=? AND custodian_id=? "
                        + "AND is_active=TRUE FOR UPDATE")) {
            row.setObject(1, key.projectId());
            row.setObject(2, actor);
            try (ResultSet result = row.executeQuery()) {
                return result.next();
            }
        }
    }

    public boolean propose(UUID proposalId,
            EntityListSnapshotLedgerCoordinator.Key key, UUID subject,
            Action action) {
        UUID proposer = activePrincipal();
        if (proposalId == null || key == null || key.projectId() == null
                || key.formId() == null
                || !EntityListManifestPreflight.validTypedFilename(key.filename())
                || proposer == null || subject == null
                || subject.equals(proposer) || action == null) {
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!authorizedCustodian(connection, proposer, key)) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO entity_list_custodian_change_proposals "
                                + "(id,project_id,form_id,filename,subject_id,initiated_by,action) "
                                + "VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING")) {
                    insert.setObject(1, proposalId);
                    insert.setObject(2, key.projectId());
                    insert.setObject(3, key.formId());
                    insert.setString(4, key.filename());
                    insert.setObject(5, subject);
                    insert.setObject(6, proposer);
                    insert.setString(7, action.name());
                    if (insert.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot create custodian attestation proposal", error);
        }
    }

    public boolean witness(UUID proposalId) {
        UUID witness = activePrincipal();
        if (proposalId == null || witness == null) {
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Read scope first, then take the established authorization
                // lock ordering before locking the proposal for transition.
                EntityListSnapshotLedgerCoordinator.Key key;
                try (PreparedStatement read = connection.prepareStatement(
                        "SELECT project_id,form_id,filename FROM "
                                + "entity_list_custodian_change_proposals WHERE id=?")) {
                    read.setObject(1, proposalId);
                    try (ResultSet row = read.executeQuery()) {
                        if (!row.next()) {
                            connection.rollback();
                            return false;
                        }
                        key = new EntityListSnapshotLedgerCoordinator.Key(
                                row.getObject(1, UUID.class),
                                row.getObject(2, UUID.class), row.getString(3));
                    }
                }
                if (!authorizedCustodian(connection, witness, key)) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT initiated_by,subject_id,state,project_id,form_id,filename "
                                + "FROM entity_list_custodian_change_proposals "
                                + "WHERE id=? FOR UPDATE")) {
                    lock.setObject(1, proposalId);
                    try (ResultSet row = lock.executeQuery()) {
                        if (!row.next() || !"PENDING".equals(row.getString("state"))
                                || witness.equals(row.getObject("initiated_by", UUID.class))
                                || witness.equals(row.getObject("subject_id", UUID.class))
                                || !key.projectId().equals(row.getObject("project_id", UUID.class))
                                || !key.formId().equals(row.getObject("form_id", UUID.class))
                                || !key.filename().equals(row.getString("filename"))) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                int count;
                try (PreparedStatement check = connection.prepareStatement(
                        "SELECT COUNT(*) FROM entity_list_custodian_change_witnesses "
                                + "WHERE proposal_id=?")) {
                    check.setObject(1, proposalId);
                    try (ResultSet row = check.executeQuery()) {
                        row.next();
                        count = row.getInt(1);
                    }
                }
                if (count >= 2) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO entity_list_custodian_change_witnesses "
                                + "(proposal_id,witness_id) VALUES (?,?) "
                                + "ON CONFLICT DO NOTHING")) {
                    insert.setObject(1, proposalId);
                    insert.setObject(2, witness);
                    if (insert.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                if (count == 1) {
                    try (PreparedStatement endorse = connection.prepareStatement(
                            "UPDATE entity_list_custodian_change_proposals "
                                    + "SET state='ENDORSED' WHERE id=? AND state='PENDING'")) {
                        endorse.setObject(1, proposalId);
                        if (endorse.executeUpdate() != 1) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot record independent custodian attestation", error);
        }
    }

    private static void rollback(Connection connection, Exception error) {
        try {
            connection.rollback();
        } catch (SQLException rollback) {
            error.addSuppressed(rollback);
        }
    }
}
