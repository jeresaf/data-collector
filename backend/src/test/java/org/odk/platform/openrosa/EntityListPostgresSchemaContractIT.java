package org.odk.platform.openrosa;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the separate test-resource schema against disposable PostgreSQL, never
 * against production or migrations. Verifies immutable histories plus deferred
 * FK semantics needed by the optimistic JDBC store transaction ordering.
 */
@Testcontainers
class EntityListPostgresSchemaContractIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID FORM = UUID.fromString("00000000-0000-0000-0000-000000000303");
    private static final String FILENAME = "households.csv";
    private static final String ID = "d6b8a5e0-79d8-46e7-aab9-5bf1a57162ad";
    private static final byte[] ORIGINAL = ("name,label,__version\n" + ID + ",First,1\n")
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] SECOND = ("name,label,__version\n" + ID + ",Updated,2\n")
            .getBytes(StandardCharsets.UTF_8);

    private static String md5(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    }

    private static final class Fixture {
        private final String schema;
        private final PGSimpleDataSource source;

        Fixture() throws Exception {
            schema = "ledger_schema_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection c = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = c.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            }
            source = new PGSimpleDataSource();
            source.setUrl(POSTGRES.getJdbcUrl());
            source.setUser(POSTGRES.getUsername());
            source.setPassword(POSTGRES.getPassword());
            source.setCurrentSchema(schema);
            try (var stream = EntityListPostgresSchemaContractIT.class
                    .getResourceAsStream("/entity-list-ledger-schema-postgres.sql")) {
                if (stream == null) {
                    throw new IllegalStateException("Schema test resource missing");
                }
                String ddl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                try (Connection c = source.getConnection();
                     var statements = c.createStatement()) {
                    for (String fragment : ddl.split("(?m)^-- split\\s*$")) {
                        if (!fragment.isBlank()) {
                            statements.execute(fragment);
                        }
                    }
                }
            }
        }

        EntityListSnapshotEvidence.Snapshot snapshot(long revision, byte[] content) throws Exception {
            return EntityListSnapshotEvidence.fromVerifiedBytes(
                    PROJECT, FORM, FILENAME, revision, Map.of("odkMediaType", "entityList"),
                    content, md5(content), 1L).orElseThrow();
        }

        void initialize() throws Exception {
            var first = snapshot(1, ORIGINAL);
            try (Connection c = source.getConnection()) {
                c.setAutoCommit(false);
                try (var head = c.prepareStatement(
                        "INSERT INTO entity_list_snapshot_heads VALUES (?,?,?,?,?)");
                     var history = c.prepareStatement(
                             "INSERT INTO entity_list_snapshot_history "
                                     + "(project_id, form_id, filename, revision, media_type, md5,"
                                     + " sha256, row_count, payload) VALUES (?,?,?,?,?,?,?,?,?)")) {
                    // Head-first bootstrap is legal only when the associated
                    // immutable snapshot gets inserted in the SAME transaction.
                    head.setObject(1, PROJECT);
                    head.setObject(2, FORM);
                    head.setString(3, FILENAME);
                    head.setLong(4, 1L);
                    head.setString(5, first.sha256());
                    head.executeUpdate();
                    history.setObject(1, PROJECT);
                    history.setObject(2, FORM);
                    history.setString(3, FILENAME);
                    history.setLong(4, 1L);
                    history.setString(5, first.mediaType());
                    history.setString(6, first.md5());
                    history.setString(7, first.sha256());
                    history.setLong(8, 1L);
                    history.setBytes(9, ORIGINAL);
                    history.executeUpdate();
                    c.commit();
                }
            }
        }

        int historyCount() throws Exception {
            try (Connection c = source.getConnection();
                 var stmt = c.createStatement();
                 var rs = stmt.executeQuery("SELECT count(*) FROM entity_list_snapshot_history")) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void proposedSchemaRetainsImmutableHistoryAndAcceptsSafeConsecutiveRevision() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var store = new JdbcEntityListSnapshotStore(fixture.source);
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        assertEquals(EntityListSnapshotLedgerCoordinator.Outcome.APPENDED,
                EntityListSnapshotLedgerCoordinator.propose(
                        store, key, Map.of("odkMediaType", "entityList"),
                        SECOND, md5(SECOND), 1L).outcome());
        assertEquals(2, fixture.historyCount());
        assertEquals(2L, store.currentHead(key).orElseThrow().evidence().revision());

        try (Connection c = fixture.source.getConnection()) {
            try (var query = c.prepareStatement(
                    "SELECT payload FROM entity_list_snapshot_history WHERE revision=1");
                 var result = query.executeQuery()) {
                assertTrue(result.next());
                assertTrue(Arrays.equals(ORIGINAL, result.getBytes(1)));
            }
            assertThrows(SQLException.class, () -> {
                try (var update = c.createStatement()) {
                    update.executeUpdate("UPDATE entity_list_snapshot_history "
                            + "SET row_count=99 WHERE revision=1");
                }
            });
            assertThrows(SQLException.class, () -> {
                try (var delete = c.createStatement()) {
                    delete.executeUpdate("DELETE FROM entity_list_snapshot_history WHERE revision=1");
                }
            });
        }
        assertEquals(2, fixture.historyCount());
    }

    @Test
    void orphanHeadCannotCommitAndConstraintsDenyInvalidRevision() throws Exception {
        var fixture = new Fixture();
        try (Connection c = fixture.source.getConnection()) {
            c.setAutoCommit(false);
            try (var statement = c.prepareStatement(
                    "INSERT INTO entity_list_snapshot_heads VALUES (?,?,?,?,?)")) {
                statement.setObject(1, PROJECT);
                statement.setObject(2, FORM);
                statement.setString(3, FILENAME);
                statement.setLong(4, 1L);
                statement.setString(5, "a".repeat(64));
                statement.executeUpdate();
                assertThrows(SQLException.class, c::commit);
                c.rollback();
            }
        }
        try (Connection c = fixture.source.getConnection();
             var stmt = c.createStatement();
             var result = stmt.executeQuery("SELECT count(*) FROM entity_list_snapshot_heads")) {
            assertTrue(result.next());
            assertEquals(0, result.getInt(1));
        }
        fixture.initialize();
        assertEquals(1, fixture.historyCount());
        try (Connection c = fixture.source.getConnection();
             var stmt = c.createStatement()) {
            assertThrows(SQLException.class, () -> stmt.executeUpdate(
                    "UPDATE entity_list_snapshot_heads SET revision=0"));
        }
        assertEquals(1, fixture.historyCount());
    }
    @Test
    void explicitAuthorizedBootstrapCreatesOnlyFirstTrustedRevision() throws Exception {
        var fixture = new Fixture();
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000310");
        UUID outsider = UUID.fromString("00000000-0000-0000-0000-000000000311");
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var authorizer = (JdbcEntityListSnapshotBootstrap.Authorization)
                (actor, requestedKey) -> owner.equals(actor) && key.equals(requestedKey);
        var bootstrap = new JdbcEntityListSnapshotBootstrap(fixture.source, authorizer);
        var metadata = Map.<String, Object>of("odkMediaType", "entityList");
        assertEquals(JdbcEntityListSnapshotBootstrap.Outcome.DENIED,
                bootstrap.initialize(outsider, key, metadata, ORIGINAL, md5(ORIGINAL), 1L));
        assertEquals(JdbcEntityListSnapshotBootstrap.Outcome.INVALID,
                bootstrap.initialize(owner, key, metadata, ORIGINAL, md5(SECOND), 1L));
        assertEquals(JdbcEntityListSnapshotBootstrap.Outcome.INVALID,
                bootstrap.initialize(owner, key, metadata, ORIGINAL, md5(ORIGINAL), 2L));
        assertEquals(0, fixture.historyCount());
        assertEquals(JdbcEntityListSnapshotBootstrap.Outcome.INITIALIZED,
                bootstrap.initialize(owner, key, metadata, ORIGINAL, md5(ORIGINAL), 1L));
        assertEquals(JdbcEntityListSnapshotBootstrap.Outcome.ALREADY_INITIALIZED,
                bootstrap.initialize(owner, key, metadata, ORIGINAL, md5(ORIGINAL), 1L));
        assertEquals(1, fixture.historyCount());
        var head = new JdbcEntityListSnapshotStore(fixture.source).currentHead(key).orElseThrow();
        assertEquals(1L, head.evidence().revision());
        assertTrue(Arrays.equals(ORIGINAL, head.bytes()));
        assertEquals(JdbcEntityListSnapshotBootstrap.Outcome.DENIED,
                bootstrap.initialize(outsider, key, metadata, SECOND, md5(SECOND), 1L));
    }

    @Test
    void concurrentAuthorizedBootstrapHasExactlyOneSuccessfulInitializer() throws Exception {
        var fixture = new Fixture();
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000310");
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var bootstrap = new JdbcEntityListSnapshotBootstrap(fixture.source,
                (actor, requestedKey) -> owner.equals(actor) && key.equals(requestedKey));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        var start = new java.util.concurrent.CountDownLatch(1);
        var completed = new java.util.concurrent.CountDownLatch(4);
        var accepted = new java.util.concurrent.atomic.AtomicInteger();
        var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        try {
            for (int i = 0; i < 4; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        var outcome = bootstrap.initialize(owner, key,
                                Map.of("odkMediaType", "entityList"),
                                ORIGINAL, md5(ORIGINAL), 1L);
                        if (outcome == JdbcEntityListSnapshotBootstrap.Outcome.INITIALIZED) {
                            accepted.incrementAndGet();
                        } else if (outcome != JdbcEntityListSnapshotBootstrap.Outcome.ALREADY_INITIALIZED) {
                            failures.add(new AssertionError("Unexpected outcome: " + outcome));
                        }
                    } catch (Throwable error) {
                        failures.add(error);
                    } finally {
                        completed.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(completed.await(20, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(failures.isEmpty(), failures.toString());
            assertEquals(1, accepted.get());
            assertEquals(1, fixture.historyCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void transactionGateRejectsRevokedManagerBeforeAnySnapshotInsert() throws Exception {
        var fixture = new Fixture();
        UUID manager = UUID.fromString("00000000-0000-0000-0000-000000000310");
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        try (Connection connection = fixture.source.getConnection();
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE entity_list_test_permissions (actor_id UUID PRIMARY KEY,"
                    + " project_id UUID NOT NULL, form_id UUID NOT NULL, allowed BOOLEAN NOT NULL)");
        }
        try (Connection connection = fixture.source.getConnection();
             var insert = connection.prepareStatement(
                     "INSERT INTO entity_list_test_permissions VALUES (?, ?, ?, TRUE)")) {
            insert.setObject(1, manager);
            insert.setObject(2, PROJECT);
            insert.setObject(3, FORM);
            insert.executeUpdate();
        }
        var bootstrap = new JdbcEntityListSnapshotBootstrap(
                fixture.source, (actor, requestedKey) ->
                        manager.equals(actor) && key.equals(requestedKey));
        JdbcEntityListSnapshotBootstrap.TransactionAuthorization gate =
                (connection, actor, requestedKey) -> {
                    try (var statement = connection.prepareStatement(
                            "SELECT allowed FROM entity_list_test_permissions "
                                    + "WHERE actor_id=? AND project_id=? AND form_id=? FOR UPDATE")) {
                        statement.setObject(1, actor);
                        statement.setObject(2, requestedKey.projectId());
                        statement.setObject(3, requestedKey.formId());
                        try (var rows = statement.executeQuery()) {
                            return rows.next() && rows.getBoolean(1);
                        }
                    }
                };
        // Permission was initially granted but revoked before the transaction
        // starts. A preflight-only check would not be sufficient authority.
        try (Connection connection = fixture.source.getConnection();
             var revoke = connection.prepareStatement(
                     "UPDATE entity_list_test_permissions SET allowed=FALSE WHERE actor_id=?")) {
            revoke.setObject(1, manager);
            assertEquals(1, revoke.executeUpdate());
        }
        assertEquals(JdbcEntityListSnapshotBootstrap.Outcome.DENIED,
                bootstrap.initializeWithTransactionAuthorization(
                        manager, key, Map.of("odkMediaType", "entityList"),
                        ORIGINAL, md5(ORIGINAL), 1L, gate));
        assertEquals(0, fixture.historyCount());

        try (Connection connection = fixture.source.getConnection();
             var restore = connection.prepareStatement(
                     "UPDATE entity_list_test_permissions SET allowed=TRUE WHERE actor_id=?")) {
            restore.setObject(1, manager);
            assertEquals(1, restore.executeUpdate());
        }
        assertEquals(JdbcEntityListSnapshotBootstrap.Outcome.INITIALIZED,
                bootstrap.initializeWithTransactionAuthorization(
                        manager, key, Map.of("odkMediaType", "entityList"),
                        ORIGINAL, md5(ORIGINAL), 1L, gate));
        assertEquals(1, fixture.historyCount());
    }

    @Test
    void transactionGateSQLExceptionRollsBackAndDoesNotInitialize() throws Exception {
        var fixture = new Fixture();
        UUID manager = UUID.fromString("00000000-0000-0000-0000-000000000310");
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var bootstrap = new JdbcEntityListSnapshotBootstrap(fixture.source,
                (actor, requestedKey) -> true);
        JdbcEntityListSnapshotBootstrap.TransactionAuthorization failed =
                (connection, actor, requestedKey) -> {
                    throw new SQLException("Simulated authorization query failure");
                };
        assertThrows(IllegalStateException.class, () ->
                bootstrap.initializeWithTransactionAuthorization(
                        manager, key, Map.of("odkMediaType", "entityList"),
                        ORIGINAL, md5(ORIGINAL), 1L, failed));
        assertEquals(0, fixture.historyCount());
    }

}
