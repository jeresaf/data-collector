package org.odk.platform.openrosa;

import org.odk.platform.security.CurrentUserService;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Unregistered TEST-SCHEMA-ONLY custody execution proof.
 *
 * Separately delegated active execution officer, not a proposer, subject,
 * witness or readiness validator. Rechecks the entire live quorum before
 * atomically changing ONE custodian and appending a one-use immutable
 * execution record. Cannot bootstrap an empty project; never changes
 * Entity List records, snapshots, Android data or production custody.
 */
public final class EntityListCustodianChangeExecutor {
    private final DataSource dataSource;
    private final CurrentUserService currentUserService;
    private final JdbcEntityListSnapshotBootstrap.TransactionAuthorization managerGate;

    public EntityListCustodianChangeExecutor(DataSource dataSource,
            CurrentUserService currentUserService,
            JdbcEntityListSnapshotBootstrap.TransactionAuthorization managerGate) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.currentUserService = Objects.requireNonNull(currentUserService);
        this.managerGate = Objects.requireNonNull(managerGate);
    }

    public boolean execute(UUID proposalId) {
        if (proposalId == null) {
            return false;
        }
        var current = currentUserService.getCurrentWebUser();
        if (current.isEmpty() || current.get().getId() == null
                || !Boolean.TRUE.equals(current.get().getIsActive())) {
            return false;
        }
        UUID officer = current.get().getId();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
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
                if (!managerGate.mayInitialize(connection, officer, key)) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement authorized = connection.prepareStatement(
                        "SELECT officer_id FROM entity_list_custodian_execution_officers "
                                + "WHERE project_id=? AND officer_id=? AND is_active=TRUE "
                                + "FOR UPDATE")) {
                    authorized.setObject(1, key.projectId());
                    authorized.setObject(2, officer);
                    try (ResultSet row = authorized.executeQuery()) {
                        if (!row.next()) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                UUID proposer;
                UUID subject;
                String action;
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT initiated_by,subject_id,action "
                                + "FROM entity_list_custodian_change_proposals "
                                + "WHERE id=? AND project_id=? AND form_id=? AND filename=? "
                                + "AND state='ENDORSED' "
                                + "AND created_at > CURRENT_TIMESTAMP - INTERVAL '24 hours' "
                                + "AND created_at <= CURRENT_TIMESTAMP FOR UPDATE")) {
                    lock.setObject(1, proposalId);
                    lock.setObject(2, key.projectId());
                    lock.setObject(3, key.formId());
                    lock.setString(4, key.filename());
                    try (ResultSet row = lock.executeQuery()) {
                        if (!row.next()) {
                            connection.rollback();
                            return false;
                        }
                        proposer = row.getObject(1, UUID.class);
                        subject = row.getObject(2, UUID.class);
                        action = row.getString(3);
                    }
                }
                UUID validator;
                try (PreparedStatement receipt = connection.prepareStatement(
                        "SELECT validated_by FROM entity_list_custodian_change_readiness "
                                + "WHERE proposal_id=? FOR UPDATE")) {
                    receipt.setObject(1, proposalId);
                    try (ResultSet row = receipt.executeQuery()) {
                        if (!row.next()) {
                            connection.rollback();
                            return false;
                        }
                        validator = row.getObject(1, UUID.class);
                    }
                }
                if (officer.equals(proposer) || officer.equals(subject)
                        || officer.equals(validator)) {
                    connection.rollback();
                    return false;
                }
                Set<UUID> witnesses = new HashSet<>();
                try (PreparedStatement read = connection.prepareStatement(
                        "SELECT witness_id FROM entity_list_custodian_change_witnesses "
                                + "WHERE proposal_id=? ORDER BY witness_id FOR UPDATE")) {
                    read.setObject(1, proposalId);
                    try (ResultSet row = read.executeQuery()) {
                        while (row.next()) {
                            UUID witness = row.getObject(1, UUID.class);
                            if (!witnesses.add(witness)
                                    || witness.equals(officer)
                                    || witness.equals(proposer)
                                    || witness.equals(subject)
                                    || witness.equals(validator)) {
                                connection.rollback();
                                return false;
                            }
                        }
                    }
                }
                if (witnesses.size() != 2
                        || proposer.equals(subject) || proposer.equals(validator)
                        || subject.equals(validator)) {
                    connection.rollback();
                    return false;
                }
                // All custodians responsible for the authorization must
                // retain their active delegation at EXECUTION time.
                Set<UUID> required = new HashSet<>(witnesses);
                required.add(proposer);
                required.add(validator);
                if (required.size() != 4) {
                    connection.rollback();
                    return false;
                }
                for (UUID custodian : required.stream().sorted().toList()) {
                    if (!activeCustodian(connection, key.projectId(), custodian)) {
                        connection.rollback();
                        return false;
                    }
                }
                // Prevent first-custodian bootstrap; keep >=4 distinct
                // custodians after a revocation for future quorum governance.
                int activeCount;
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT custodian_id FROM entity_list_grant_custodians "
                                + "WHERE project_id=? AND is_active=TRUE "
                                + "ORDER BY custodian_id FOR UPDATE")) {
                    count.setObject(1, key.projectId());
                    try (ResultSet row = count.executeQuery()) {
                        activeCount = 0;
                        while (row.next()) {
                            activeCount++;
                        }
                    }
                }
                if (activeCount < 4) {
                    connection.rollback();
                    return false;
                }
                int changed;
                if ("APPOINT".equals(action)) {
                    try (PreparedStatement appoint = connection.prepareStatement(
                            "INSERT INTO entity_list_grant_custodians "
                                    + "(project_id,custodian_id,is_active) VALUES (?,?,TRUE) "
                                    + "ON CONFLICT (project_id,custodian_id) "
                                    + "DO UPDATE SET is_active=TRUE "
                                    + "WHERE entity_list_grant_custodians.is_active=FALSE")) {
                        appoint.setObject(1, key.projectId());
                        appoint.setObject(2, subject);
                        changed = appoint.executeUpdate();
                    }
                } else if ("REVOKE".equals(action) && activeCount >= 5) {
                    try (PreparedStatement revoke = connection.prepareStatement(
                            "UPDATE entity_list_grant_custodians SET is_active=FALSE "
                                    + "WHERE project_id=? AND custodian_id=? AND is_active=TRUE")) {
                        revoke.setObject(1, key.projectId());
                        revoke.setObject(2, subject);
                        changed = revoke.executeUpdate();
                    }
                } else {
                    connection.rollback();
                    return false;
                }
                if (changed != 1) {
                    connection.rollback();
                    return false;
                }
                // The unique proposal key makes concurrent executions one-use;
                // audit failure rolls the custody change back.
                try (PreparedStatement audit = connection.prepareStatement(
                        "INSERT INTO entity_list_custodian_change_executions "
                                + "(proposal_id,executed_by,action,project_id,subject_id) "
                                + "VALUES (?,?,?,?,?) ON CONFLICT (proposal_id) DO NOTHING")) {
                    audit.setObject(1, proposalId);
                    audit.setObject(2, officer);
                    audit.setString(3, action);
                    audit.setObject(4, key.projectId());
                    audit.setObject(5, subject);
                    if (audit.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    error.addSuppressed(rollback);
                }
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot execute audited custody change", error);
        }
    }

    private static boolean activeCustodian(Connection connection, UUID projectId, UUID actor)
            throws SQLException {
        try (PreparedStatement check = connection.prepareStatement(
                "SELECT custodian_id FROM entity_list_grant_custodians "
                        + "WHERE project_id=? AND custodian_id=? AND is_active=TRUE FOR UPDATE")) {
            check.setObject(1, projectId);
            check.setObject(2, actor);
            try (ResultSet row = check.executeQuery()) {
                return row.next();
            }
        }
    }
}
