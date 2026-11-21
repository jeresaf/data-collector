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
 * Inactive, test-schema-only receipt reconciliation after an unknown commit
 * acknowledgement. The immutable execution receipt is the authority for
 * whether a proposed custody change committed; retrying the write is NOT.
 *
 * Reconciliation locks the proposal row to wait for an in-flight execution
 * to commit or roll back before reporting NOT_COMMITTED.
 * No permission changes, production endpoints or migrations.
 */
public final class EntityListCustodianExecutionReconciliation {
    public enum Status {
        COMMITTED, NOT_COMMITTED, UNKNOWN_PROPOSAL, INCONSISTENT, UNAUTHORIZED
    }

    private final DataSource source;
    private final CurrentUserService currentUserService;
    private final JdbcEntityListSnapshotBootstrap.TransactionAuthorization managerGate;

    public EntityListCustodianExecutionReconciliation(DataSource source,
            CurrentUserService currentUserService,
            JdbcEntityListSnapshotBootstrap.TransactionAuthorization managerGate) {
        this.source = Objects.requireNonNull(source);
        this.currentUserService = Objects.requireNonNull(currentUserService);
        this.managerGate = Objects.requireNonNull(managerGate);
    }

    public Status check(UUID proposalId) {
        if (proposalId == null) {
            return Status.UNKNOWN_PROPOSAL;
        }
        var principal = currentUserService.getCurrentWebUser();
        if (principal.isEmpty() || principal.get().getId() == null
                || !Boolean.TRUE.equals(principal.get().getIsActive())) {
            return Status.UNAUTHORIZED;
        }
        UUID actor = principal.get().getId();
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                EntityListSnapshotLedgerCoordinator.Key key;
                try (PreparedStatement scope = connection.prepareStatement(
                        "SELECT project_id,form_id,filename FROM "
                                + "entity_list_custodian_change_proposals WHERE id=?")) {
                    scope.setObject(1, proposalId);
                    try (ResultSet row = scope.executeQuery()) {
                        if (!row.next()) {
                            connection.rollback();
                            return Status.UNKNOWN_PROPOSAL;
                        }
                        key = new EntityListSnapshotLedgerCoordinator.Key(
                                row.getObject(1, UUID.class),
                                row.getObject(2, UUID.class), row.getString(3));
                    }
                }
                if (!managerGate.mayInitialize(connection, actor, key)) {
                    connection.rollback();
                    return Status.UNAUTHORIZED;
                }
                try (PreparedStatement officer = connection.prepareStatement(
                        "SELECT officer_id FROM entity_list_custodian_execution_officers "
                                + "WHERE project_id=? AND officer_id=? AND is_active=TRUE "
                                + "FOR UPDATE")) {
                    officer.setObject(1, key.projectId());
                    officer.setObject(2, actor);
                    try (ResultSet row = officer.executeQuery()) {
                        if (!row.next()) {
                            connection.rollback();
                            return Status.UNAUTHORIZED;
                        }
                    }
                }

                UUID subject;
                String action;
                // Execution holds this same row lock. A status read MUST
                // serialize behind the competing transaction before deciding
                // that a missing receipt means NOT_COMMITTED.
                try (PreparedStatement proposal = connection.prepareStatement(
                        "SELECT subject_id,action FROM entity_list_custodian_change_proposals "
                                + "WHERE id=? AND project_id=? AND form_id=? "
                                + "AND filename=? FOR UPDATE")) {
                    proposal.setObject(1, proposalId);
                    proposal.setObject(2, key.projectId());
                    proposal.setObject(3, key.formId());
                    proposal.setString(4, key.filename());
                    try (ResultSet row = proposal.executeQuery()) {
                        if (!row.next()) {
                            connection.rollback();
                            return Status.INCONSISTENT;
                        }
                        subject = row.getObject(1, UUID.class);
                        action = row.getString(2);
                    }
                }
                Status status;
                try (PreparedStatement receipt = connection.prepareStatement(
                        "SELECT project_id,subject_id,action FROM "
                                + "entity_list_custodian_change_executions "
                                + "WHERE proposal_id=?")) {
                    receipt.setObject(1, proposalId);
                    try (ResultSet row = receipt.executeQuery()) {
                        if (!row.next()) {
                            status = Status.NOT_COMMITTED;
                        } else if (key.projectId().equals(row.getObject(1, UUID.class))
                                && subject.equals(row.getObject(2, UUID.class))
                                && action.equals(row.getString(3))) {
                            status = Status.COMMITTED;
                        } else {
                            status = Status.INCONSISTENT;
                        }
                    }
                }
                connection.commit();
                return status;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot reconcile custody execution receipt", error);
        }
    }
}
