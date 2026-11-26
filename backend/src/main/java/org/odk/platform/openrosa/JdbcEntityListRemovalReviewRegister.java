package org.odk.platform.openrosa;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Inactive, opt-in JDBC review register. No Spring registration or Flyway
 * migration. A decision is evidence only: it never publishes a revision,
 * creates tombstones or deletes a local/mobile Entity.
 *
 * Requester and reviewer UUIDs are claims until a future authenticated
 * service binds both to current principals and independent project roles.
 */
public final class JdbcEntityListRemovalReviewRegister {
    public enum Decision { APPROVED, REJECTED }

    private final DataSource dataSource;

    public JdbcEntityListRemovalReviewRegister(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    public record Review(UUID id, UUID requester, UUID reviewer,
                         EntityListSnapshotLedgerCoordinator.Key key,
                         long expectedRevision, String expectedSha256,
                         long candidateRevision, String candidateSha256,
                         Set<UUID> missingIds, String justification,
                         String state) {
        public Review {
            missingIds = Set.copyOf(missingIds);
        }
    }

    /** Fails closed on duplicate review IDs or unavailable audit storage. */
    public UUID submit(UUID id, EntityListRemovalReviewRequest.Request request) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(request);
        var head = request.expectedHead();
        var proposed = request.candidate();
        if (!head.projectId().equals(proposed.projectId())
                || !head.formId().equals(proposed.formId())
                || !head.filename().equals(proposed.filename())
                || !head.mediaType().equals(proposed.mediaType())
                || proposed.revision() != head.revision() + 1) {
            throw new IllegalArgumentException("Review scope/revision mismatch");
        }
        String sql = "INSERT INTO entity_list_removal_reviews "
                + "(id,requested_by,project_id,form_id,filename,expected_revision,"
                + "expected_sha256,candidate_revision,candidate_sha256,missing_ids,"
                + "justification,state) VALUES (?,?,?,?,?,?,?,?,?,?,?,'PENDING')";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setObject(1, id);
            insert.setObject(2, request.requestedBy());
            insert.setObject(3, head.projectId());
            insert.setObject(4, head.formId());
            insert.setString(5, head.filename());
            insert.setLong(6, head.revision());
            insert.setString(7, head.sha256());
            insert.setLong(8, proposed.revision());
            insert.setString(9, proposed.sha256());
            insert.setString(10, encode(request.missingIds()));
            insert.setString(11, request.justification());
            if (insert.executeUpdate() != 1) {
                throw new IllegalStateException("Review request insert did not write one row");
            }
            return id;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot persist removal review request", e);
        }
    }

    /**
     * Same-transaction row locking provides exactly one terminal decision.
     * A requester cannot review their own request, including a rejection.
     * Calling decide does NOT authorize publication; the candidate must
     * still be reverified and reauthorized at a separate future commit gate.
     */
    public boolean decide(UUID id, UUID reviewer, Decision decision) {
        // Unsafe legacy entry point cannot authorize a decision with only a
        // caller-supplied UUID. Retained for binary/API compatibility.
        return false;
    }

    /**
     * Strict opt-in path: verify current reviewer authority inside the same
     * transaction holding the review row lock, before the terminal decision.
     * The caller must bind reviewer to the current server principal.
     */
    enum DecisionOutcome {
        DECIDED, INVALID_STATE, SELF_ACTION, PERMISSION_DENIED
    }

    public boolean decideAuthorized(UUID id, UUID reviewer, Decision decision,
            JdbcEntityListSnapshotBootstrap.TransactionAuthorization authorization) {
        return decideAuthorizedOutcome(id, reviewer, decision, authorization)
                == DecisionOutcome.DECIDED;
    }

    DecisionOutcome decideAuthorizedOutcome(
            UUID id, UUID reviewer, Decision decision,
            JdbcEntityListSnapshotBootstrap.TransactionAuthorization authorization) {
        Objects.requireNonNull(authorization);
        if (id == null || reviewer == null || decision == null) {
            return DecisionOutcome.INVALID_STATE;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String read = "SELECT requested_by,state,project_id,form_id,filename "
                        + "FROM entity_list_removal_reviews WHERE id=? FOR UPDATE";
                try (PreparedStatement query = connection.prepareStatement(read)) {
                    query.setObject(1, id);
                    try (ResultSet row = query.executeQuery()) {
                        if (!row.next() || !"PENDING".equals(row.getString("state"))) {
                            connection.rollback();
                            return DecisionOutcome.INVALID_STATE;
                        }
                        if (reviewer.equals(row.getObject("requested_by", UUID.class))) {
                            connection.rollback();
                            return DecisionOutcome.SELF_ACTION;
                        }
                        var key = new EntityListSnapshotLedgerCoordinator.Key(
                                row.getObject("project_id", UUID.class),
                                row.getObject("form_id", UUID.class),
                                row.getString("filename"));
                        if (!authorization.mayInitialize(connection, reviewer, key)) {
                            connection.rollback();
                            return DecisionOutcome.PERMISSION_DENIED;
                        }
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE entity_list_removal_reviews "
                                + "SET state=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP "
                                + "WHERE id=? AND state='PENDING'")) {
                    update.setString(1, decision.name());
                    update.setObject(2, reviewer);
                    update.setObject(3, id);
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return DecisionOutcome.INVALID_STATE;
                    }
                }
                connection.commit();
                return DecisionOutcome.DECIDED;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot record removal review decision", e);
        }
    }

    public Review get(UUID id) {
        Objects.requireNonNull(id);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT * FROM entity_list_removal_reviews WHERE id=?")) {
            query.setObject(1, id);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                UUID reviewer = row.getObject("reviewed_by", UUID.class);
                return new Review(
                        row.getObject("id", UUID.class),
                        row.getObject("requested_by", UUID.class),
                        reviewer,
                        new EntityListSnapshotLedgerCoordinator.Key(
                                row.getObject("project_id", UUID.class),
                                row.getObject("form_id", UUID.class),
                                row.getString("filename")),
                        row.getLong("expected_revision"),
                        row.getString("expected_sha256"),
                        row.getLong("candidate_revision"),
                        row.getString("candidate_sha256"),
                        decode(row.getString("missing_ids")),
                        row.getString("justification"), row.getString("state"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read removal review", e);
        }
    }

    private static String encode(Set<UUID> ids) {
        return ids.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
    }

    private static Set<UUID> decode(String encoded) {
        var ids = new TreeSet<UUID>();
        for (String value : encoded.split(",")) {
            ids.add(UUID.fromString(value));
        }
        return Set.copyOf(ids);
    }
}
