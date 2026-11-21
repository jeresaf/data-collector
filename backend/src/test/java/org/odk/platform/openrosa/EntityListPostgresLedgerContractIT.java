package org.odk.platform.openrosa;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PostgreSQL Testcontainers adapter for contract testing. Not a production component,
 * not a migration, and not installed as a Spring bean.
 *
 * The head row CAS and append-only revision row are written in one SQL
 * transaction. The head row is pre-seeded by this test's trusted fixture only.
 */
@Testcontainers
class EntityListPostgresLedgerContractIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID FORM = UUID.fromString("00000000-0000-0000-0000-000000000303");
    private static final String ID1 = "d6b8a5e0-79d8-46e7-aab9-5bf1a57162ad";
    private static final String ID2 = "34f2ce6b-34e3-49e0-9db8-5c194fca56da";
    private static final byte[] OLD = ("name,label,__version\n" + ID1 + ",Original,1\n"
            + ID2 + ",Second,1\n").getBytes(StandardCharsets.UTF_8);
    private static final byte[] UPDATED = ("name,label,__version\n" + ID1 + ",Updated,2\n"
            + ID2 + ",Second,2\n").getBytes(StandardCharsets.UTF_8);
    private static final byte[] REMOVAL = ("name,label,__version\n" + ID1 + ",Updated,2\n")
            .getBytes(StandardCharsets.UTF_8);
    private static final Map<String, Object> TYPE = Map.of("odkMediaType", "entityList");
    private static final EntityListSnapshotLedgerCoordinator.Key KEY =
            new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, "households.csv");

    private static String md5(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    }

    /**
     * Deliberately narrow, test-only SQL contract. The backing ledger rows are
     * insert-only: the CAS update changes the head pointer, not historical rows.
     */
    private static final class JdbcFixtureStore implements EntityListSnapshotLedgerCoordinator.Store {
        private final String schema;

        JdbcFixtureStore() throws Exception {
            schema = "ledger_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection c = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                    var statement = c.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            }
            try (Connection c = connect(); var s = c.createStatement()) {
                s.execute("CREATE TABLE head (project_id UUID NOT NULL, form_id UUID NOT NULL,"
                        + " filename VARCHAR(255) NOT NULL, revision BIGINT NOT NULL,"
                        + " sha256 VARCHAR(64) NOT NULL, PRIMARY KEY(project_id, form_id, filename))");
                s.execute("CREATE TABLE history (project_id UUID NOT NULL, form_id UUID NOT NULL,"
                        + " filename VARCHAR(255) NOT NULL, revision BIGINT NOT NULL,"
                        + " media_type VARCHAR(32) NOT NULL, md5 VARCHAR(32) NOT NULL,"
                        + " sha256 VARCHAR(64) NOT NULL, row_count BIGINT NOT NULL,"
                        + " payload BYTEA NOT NULL,"
                        + " PRIMARY KEY(project_id, form_id, filename, revision))");
            }
            var first = EntityListSnapshotEvidence.fromVerifiedBytes(
                    PROJECT, FORM, KEY.filename(), 1L, TYPE, OLD, md5(OLD), 2L).orElseThrow();
            try (Connection c = connect()) {
                c.setAutoCommit(false);
                try (var seedHistory = c.prepareStatement(
                        "INSERT INTO history VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
                     var seedHead = c.prepareStatement("INSERT INTO head VALUES (?, ?, ?, ?, ?)")) {
                    bindHistory(seedHistory, first, OLD);
                    seedHistory.executeUpdate();
                    bindKey(seedHead, KEY);
                    seedHead.setLong(4, first.revision());
                    seedHead.setString(5, first.sha256());
                    seedHead.executeUpdate();
                    c.commit();
                }
            }
        }

        private Connection connect() throws SQLException {
            Connection c = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            try (var statement = c.createStatement()) {
                statement.execute("SET search_path TO " + schema);
            } catch (SQLException e) {
                c.close();
                throw e;
            }
            return c;
        }

        private static void bindKey(PreparedStatement p,
                EntityListSnapshotLedgerCoordinator.Key key) throws SQLException {
            p.setObject(1, key.projectId());
            p.setObject(2, key.formId());
            p.setString(3, key.filename());
        }

        private static void bindHistory(PreparedStatement p,
                EntityListSnapshotEvidence.Snapshot evidence, byte[] bytes) throws SQLException {
            p.setObject(1, evidence.projectId());
            p.setObject(2, evidence.formId());
            p.setString(3, evidence.filename());
            p.setLong(4, evidence.revision());
            p.setString(5, evidence.mediaType());
            p.setString(6, evidence.md5());
            p.setString(7, evidence.sha256());
            p.setLong(8, evidence.rowCount());
            p.setBytes(9, bytes);
        }

        @Override
        public Optional<EntityListSnapshotLedgerCoordinator.Head> currentHead(
                EntityListSnapshotLedgerCoordinator.Key key) {
            try (Connection c = connect();
                 PreparedStatement p = c.prepareStatement(
                         "SELECT h.revision, r.media_type, r.md5, r.sha256, r.row_count, r.payload "
                                 + "FROM head h JOIN history r ON r.project_id=h.project_id "
                                 + "AND r.form_id=h.form_id AND r.filename=h.filename "
                                 + "AND r.revision=h.revision WHERE h.project_id=? AND h.form_id=? "
                                 + "AND h.filename=?")) {
                bindKey(p, key);
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    var snapshot = new EntityListSnapshotEvidence.Snapshot(
                            key.projectId(), key.formId(), key.filename(),
                            rs.getString("media_type"), rs.getLong("revision"),
                            rs.getString("md5"), rs.getString("sha256"), rs.getLong("row_count"));
                    return Optional.of(new EntityListSnapshotLedgerCoordinator.Head(
                            snapshot, rs.getBytes("payload")));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("SQL contract test failed", e);
            }
        }

        @Override
        public boolean compareAndAppend(EntityListSnapshotLedgerCoordinator.Key key,
                EntityListSnapshotLedgerCoordinator.Head expected,
                EntityListSnapshotLedgerCoordinator.Head proposed) {
            try (Connection c = connect()) {
                c.setAutoCommit(false);
                try {
                    int changed;
                    try (var p = c.prepareStatement(
                            "UPDATE head SET revision=?, sha256=? "
                                    + "WHERE project_id=? AND form_id=? AND filename=? "
                                    + "AND revision=? AND sha256=?")) {
                        p.setLong(1, proposed.evidence().revision());
                        p.setString(2, proposed.evidence().sha256());
                        p.setObject(3, key.projectId());
                        p.setObject(4, key.formId());
                        p.setString(5, key.filename());
                        p.setLong(6, expected.evidence().revision());
                        p.setString(7, expected.evidence().sha256());
                        changed = p.executeUpdate();
                    }
                    if (changed != 1) {
                        c.rollback();
                        return false;
                    }
                    try (var p = c.prepareStatement(
                            "INSERT INTO history VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                        bindHistory(p, proposed.evidence(), proposed.bytes());
                        p.executeUpdate();
                    }
                    c.commit();
                    return true;
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("SQL contract append failed", e);
            }
        }

        void injectConflictingRevisionTwo() throws Exception {
            var conflicting = EntityListSnapshotEvidence.fromVerifiedBytes(
                    PROJECT, FORM, KEY.filename(), 2L, TYPE,
                    UPDATED, md5(UPDATED), 2L).orElseThrow();
            try (Connection c = connect();
                 var p = c.prepareStatement(
                         "INSERT INTO history VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                bindHistory(p, conflicting, UPDATED);
                p.executeUpdate();
            }
        }

        int historyCount() throws SQLException {
            try (Connection c = connect(); var s = c.createStatement();
                 var rs = s.executeQuery("SELECT COUNT(*) FROM history")) {
                rs.next();
                return rs.getInt(1);
            }
        }

        EntityListSnapshotLedgerCoordinator.Head historyAt(int revision) throws SQLException {
            try (Connection c = connect(); var p = c.prepareStatement(
                    "SELECT * FROM history WHERE project_id=? AND form_id=? "
                            + "AND filename=? AND revision=?")) {
                bindKey(p, KEY);
                p.setInt(4, revision);
                try (var rs = p.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("Missing ledger history revision");
                    }
                    var evidence = new EntityListSnapshotEvidence.Snapshot(
                            PROJECT, FORM, KEY.filename(), rs.getString("media_type"),
                            rs.getLong("revision"), rs.getString("md5"), rs.getString("sha256"),
                            rs.getLong("row_count"));
                    return new EntityListSnapshotLedgerCoordinator.Head(
                            evidence, rs.getBytes("payload"));
                }
            }
        }
    }

    @Test
    void atomicSqlAppendPreservesPriorSnapshotAndRetryDoesNotDuplicate() throws Exception {
        var store = new JdbcFixtureStore();
        var result = EntityListSnapshotLedgerCoordinator.propose(
                store, KEY, TYPE, UPDATED, md5(UPDATED), 2L);
        assertEquals(EntityListSnapshotLedgerCoordinator.Outcome.APPENDED, result.outcome());
        assertEquals(2, store.historyCount());
        assertTrue(java.util.Arrays.equals(OLD, store.historyAt(1).bytes()));
        assertTrue(java.util.Arrays.equals(UPDATED, store.historyAt(2).bytes()));
        assertEquals(EntityListSnapshotLedgerCoordinator.Outcome.UNCHANGED,
                EntityListSnapshotLedgerCoordinator.propose(
                        store, KEY, TYPE, UPDATED, md5(UPDATED), 2L).outcome());
        assertEquals(2, store.historyCount());
    }

    @Test
    void concurrentProposalsNeverCreateDuplicateRevisionRows() throws Exception {
        var store = new JdbcFixtureStore();
        int workers = 4;
        var executor = Executors.newFixedThreadPool(workers);
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(workers);
        var success = new AtomicInteger();
        var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        try {
            for (int i = 0; i < workers; i++) {
                executor.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        var outcome = EntityListSnapshotLedgerCoordinator.propose(
                                store, KEY, TYPE, UPDATED, md5(UPDATED), 2L).outcome();
                        if (outcome == EntityListSnapshotLedgerCoordinator.Outcome.APPENDED) {
                            success.incrementAndGet();
                        } else if (outcome != EntityListSnapshotLedgerCoordinator.Outcome.CONFLICT
                                && outcome != EntityListSnapshotLedgerCoordinator.Outcome.UNCHANGED) {
                            failures.add(new AssertionError("Unexpected outcome: " + outcome));
                        }
                    } catch (Throwable e) {
                        failures.add(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(20, TimeUnit.SECONDS));
            assertTrue(failures.isEmpty(), failures.toString());
            assertEquals(1, success.get());
            assertEquals(2, store.historyCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void removalDoesNotReachSqlAppendOrChangeHistory() throws Exception {
        var store = new JdbcFixtureStore();
        var result = EntityListSnapshotLedgerCoordinator.propose(
                store, KEY, TYPE, REMOVAL, md5(REMOVAL), 1L);
        assertEquals(EntityListSnapshotLedgerCoordinator.Outcome.REMOVAL_APPROVAL_REQUIRED,
                result.outcome());
        assertEquals(1, store.historyCount());
        assertEquals(1L, store.currentHead(KEY).orElseThrow().evidence().revision());
    }
    @Test
    void historyInsertCollisionRollsBackHeadCompareAndSwap() throws Exception {
        var store = new JdbcFixtureStore();
        // Simulates an unexpected unique-key collision after UPDATE head
        // succeeds but before the immutable revision INSERT can commit.
        store.injectConflictingRevisionTwo();
        assertEquals(2, store.historyCount());
        assertThrows(IllegalStateException.class, () ->
                EntityListSnapshotLedgerCoordinator.propose(
                        store, KEY, TYPE, UPDATED, md5(UPDATED), 2L));

        // Must still point to revision 1; the attempted update is rolled back.
        assertEquals(1L, store.currentHead(KEY).orElseThrow().evidence().revision());
        assertTrue(java.util.Arrays.equals(
                OLD, store.currentHead(KEY).orElseThrow().bytes()));
        assertEquals(2, store.historyCount());
        assertTrue(java.util.Arrays.equals(OLD, store.historyAt(1).bytes()));
    }

}
