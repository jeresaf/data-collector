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

/**
 * Opt-in, unregistered, TEST-SCHEMA-ONLY approval-readiness seal.
 *
 * A seal is an immutable, one-use validation receipt. It NEVER writes
 * snapshot history/heads, deletes Entities, or publishes tombstones.
 * A future production publisher must NOT interpret this receipt alone as
 * authority to execute a removal.
 */
public final class JdbcEntityListRemovalApprovalSeal {
    private final DataSource dataSource;

    public JdbcEntityListRemovalApprovalSeal(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    /**
     * Validates the approved request against the locked authoritative head,
     * verified immutable original bytes and exact candidate bytes. Records
     * at most one immutable seal for a review in the SAME transaction.
     * This public legacy entry point fails closed. The internal path accepts
     * only the actor resolved by EntityListAuthenticatedSealService and
     * still rechecks authority within the same database transaction.
     */
    public boolean validateAndSeal(
            UUID reviewId, UUID executorId,
            EntityListSnapshotLedgerCoordinator.Head candidate,
            JdbcEntityListSnapshotBootstrap.TransactionAuthorization authorization) {
        // Legacy caller-supplied executor UUID can never authorize a receipt.
        // A validated principal-bound facade must invoke the internal path.
        return false;
    }

    boolean validateAndSealAuthorized(
            UUID reviewId, UUID executorId,
            EntityListSnapshotLedgerCoordinator.Head candidate,
            JdbcEntityListSnapshotBootstrap.TransactionAuthorization authorization) {
        Objects.requireNonNull(authorization);
        if (reviewId == null || executorId == null || candidate == null
                || candidate.evidence() == null || candidate.bytes() == null) {
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String sql = "SELECT r.requested_by,r.reviewed_by,r.state,r.project_id,r.form_id,"
                        + "r.filename,r.expected_revision,r.expected_sha256,"
                        + "r.candidate_revision,r.candidate_sha256,r.missing_ids,r.justification "
                        + "FROM entity_list_removal_reviews r WHERE r.id=? FOR UPDATE";
                UUID requester;
                EntityListSnapshotLedgerCoordinator.Key key;
                long expectedRevision;
                String expectedSha;
                Set<UUID> missingIds;
                String justification;
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setObject(1, reviewId);
                    try (ResultSet row = stmt.executeQuery()) {
                        if (!row.next()
                                || !"APPROVED".equals(row.getString("state"))
                                || row.getObject("reviewed_by") == null
                                || executorId.equals(row.getObject("requested_by", UUID.class))
                                || executorId.equals(row.getObject("reviewed_by", UUID.class))) {
                            connection.rollback();
                            return false;
                        }
                        requester = row.getObject("requested_by", UUID.class);
                        key = new EntityListSnapshotLedgerCoordinator.Key(
                                row.getObject("project_id", UUID.class),
                                row.getObject("form_id", UUID.class),
                                row.getString("filename"));
                        expectedRevision = row.getLong("expected_revision");
                        expectedSha = row.getString("expected_sha256");
                        missingIds = decode(row.getString("missing_ids"));
                        justification = row.getString("justification");
                        if (candidate.evidence().revision() != row.getLong("candidate_revision")
                                || !candidate.evidence().sha256()
                                        .equals(row.getString("candidate_sha256"))
                                || !key.projectId().equals(candidate.evidence().projectId())
                                || !key.formId().equals(candidate.evidence().formId())
                                || !key.filename().equals(candidate.evidence().filename())) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                if (!authorization.mayInitialize(connection, executorId, key)) {
                    connection.rollback();
                    return false;
                }
                // Lock current head to prevent concurrent audited CAS updates.
                // The five-column FK ensures its exact revision/digest exists.
                try (PreparedStatement stmt = connection.prepareStatement(
                        "SELECT revision,sha256 FROM entity_list_snapshot_heads "
                                + "WHERE project_id=? AND form_id=? AND filename=? FOR UPDATE")) {
                    bindKey(stmt, key);
                    try (ResultSet row = stmt.executeQuery()) {
                        if (!row.next() || row.getLong("revision") != expectedRevision
                                || !expectedSha.equals(row.getString("sha256"))) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                EntityListSnapshotEvidence.Snapshot original;
                byte[] originalBytes;
                try (PreparedStatement stmt = connection.prepareStatement(
                        "SELECT revision,media_type,md5,sha256,row_count,payload "
                                + "FROM entity_list_snapshot_history "
                                + "WHERE project_id=? AND form_id=? AND filename=? "
                                + "AND revision=? AND sha256=?")) {
                    bindKey(stmt, key);
                    stmt.setLong(4, expectedRevision);
                    stmt.setString(5, expectedSha);
                    try (ResultSet row = stmt.executeQuery()) {
                        if (!row.next()) {
                            throw new IllegalStateException("Approved review history is missing");
                        }
                        original = new EntityListSnapshotEvidence.Snapshot(
                                key.projectId(), key.formId(), key.filename(),
                                row.getString("media_type"), row.getLong("revision"),
                                row.getString("md5"), row.getString("sha256"),
                                row.getLong("row_count"));
                        originalBytes = row.getBytes("payload");
                    }
                }
                var previous = new EntityListSnapshotLedgerCoordinator.Head(
                        original, originalBytes);
                var request = EntityListRemovalReviewRequest.prepare(
                        requester, justification, previous, candidate);
                if (request.isEmpty()
                        || !request.get().missingIds().equals(missingIds)
                        || request.get().expectedHead().revision() != expectedRevision
                        || !request.get().expectedHead().sha256().equals(expectedSha)) {
                    connection.rollback();
                    return false;
                }
                // Unique review_id prevents approval reuse. This is a receipt
                // ONLY: no change to the current head or immutable history.
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO entity_list_removal_validation_seals "
                                + "(review_id,validated_by,project_id,form_id,filename,"
                                + "expected_revision,expected_sha256,candidate_sha256) "
                                + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (review_id) DO NOTHING")) {
                    insert.setObject(1, reviewId);
                    insert.setObject(2, executorId);
                    insert.setObject(3, key.projectId());
                    insert.setObject(4, key.formId());
                    insert.setString(5, key.filename());
                    insert.setLong(6, expectedRevision);
                    insert.setString(7, expectedSha);
                    insert.setString(8, candidate.evidence().sha256());
                    if (insert.executeUpdate() != 1) {
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
            throw new IllegalStateException("Cannot seal removal approval validation", error);
        }
    }

    private static void bindKey(PreparedStatement stmt,
            EntityListSnapshotLedgerCoordinator.Key key) throws SQLException {
        stmt.setObject(1, key.projectId());
        stmt.setObject(2, key.formId());
        stmt.setString(3, key.filename());
    }

    private static Set<UUID> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("Removal ID evidence missing");
        }
        Set<UUID> ids = new TreeSet<>();
        for (String value : encoded.split(",")) {
            if (!ids.add(UUID.fromString(value))) {
                throw new IllegalArgumentException("Duplicate removed ID");
            }
        }
        return Set.copyOf(ids);
    }
}
