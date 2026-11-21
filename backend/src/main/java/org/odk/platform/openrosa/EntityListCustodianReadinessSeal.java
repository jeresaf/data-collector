package org.odk.platform.openrosa;

import org.odk.platform.security.CurrentUserService;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

/**
 * Inactive, test-schema-only one-use custody-change readiness receipt.
 * This NEVER appoints, revokes, bootstraps or recovers custodians.
 *
 * A distinct active custodian validates an endorsed, unexpired proposal,
 * while its locked two-witness quorum is rechecked. The database clock is
 * authoritative and the unique receipt prevents replay.
 */
public final class EntityListCustodianReadinessSeal {
    private final DataSource dataSource;
    private final CurrentUserService currentUserService;
    private final JdbcEntityListSnapshotBootstrap.TransactionAuthorization managerGate;

    public EntityListCustodianReadinessSeal(DataSource dataSource,
            CurrentUserService currentUserService,
            JdbcEntityListSnapshotBootstrap.TransactionAuthorization managerGate) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.currentUserService = Objects.requireNonNull(currentUserService);
        this.managerGate = Objects.requireNonNull(managerGate);
    }

    public boolean seal(UUID proposalId) {
        if (proposalId == null) {
            return false;
        }
        var current = currentUserService.getCurrentWebUser();
        if (current.isEmpty() || current.get().getId() == null
                || !Boolean.TRUE.equals(current.get().getIsActive())) {
            return false;
        }
        UUID validator = current.get().getId();
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
                if (!managerGate.mayInitialize(connection, validator, key)) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement permission = connection.prepareStatement(
                        "SELECT custodian_id FROM entity_list_grant_custodians "
                                + "WHERE project_id=? AND custodian_id=? "
                                + "AND is_active=TRUE FOR UPDATE")) {
                    permission.setObject(1, key.projectId());
                    permission.setObject(2, validator);
                    try (ResultSet row = permission.executeQuery()) {
                        if (!row.next()) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                UUID proposer;
                UUID subject;
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT initiated_by,subject_id FROM entity_list_custodian_change_proposals "
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
                    }
                }
                if (validator.equals(proposer) || validator.equals(subject)) {
                    connection.rollback();
                    return false;
                }
                var witnesses = new HashSet<UUID>();
                try (PreparedStatement read = connection.prepareStatement(
                        "SELECT witness_id FROM entity_list_custodian_change_witnesses "
                                + "WHERE proposal_id=? ORDER BY witness_id FOR UPDATE")) {
                    read.setObject(1, proposalId);
                    try (ResultSet row = read.executeQuery()) {
                        while (row.next()) {
                            UUID id = row.getObject(1, UUID.class);
                            if (id.equals(validator) || id.equals(proposer)
                                    || id.equals(subject) || !witnesses.add(id)) {
                                connection.rollback();
                                return false;
                            }
                        }
                    }
                }
                if (witnesses.size() != 2) {
                    connection.rollback();
                    return false;
                }
                // Witnesses must still be active custodians at validation time.
                for (UUID witness : witnesses.stream().sorted().toList()) {
                    try (PreparedStatement check = connection.prepareStatement(
                            "SELECT custodian_id FROM entity_list_grant_custodians "
                                    + "WHERE project_id=? AND custodian_id=? "
                                    + "AND is_active=TRUE FOR UPDATE")) {
                        check.setObject(1, key.projectId());
                        check.setObject(2, witness);
                        try (ResultSet row = check.executeQuery()) {
                            if (!row.next()) {
                                connection.rollback();
                                return false;
                            }
                        }
                    }
                }
                try (PreparedStatement receipt = connection.prepareStatement(
                        "INSERT INTO entity_list_custodian_change_readiness "
                                + "(proposal_id,validated_by) VALUES (?,?) "
                                + "ON CONFLICT (proposal_id) DO NOTHING")) {
                    receipt.setObject(1, proposalId);
                    receipt.setObject(2, validator);
                    if (receipt.executeUpdate() != 1) {
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
            throw new IllegalStateException("Cannot validate custodian readiness", error);
        }
    }
}
