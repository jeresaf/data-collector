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
        var before = store.currentHead(key).orElseThrow();
        var next = new EntityListSnapshotLedgerCoordinator.Head(
                fixture.snapshot(2L, SECOND), SECOND);
        // The JDBC Store interface is deliberately unable to publish.
        assertFalse(store.compareAndAppend(key, before, next));
        assertEquals(1, fixture.historyCount());
        UUID actor = UUID.fromString("00000000-0000-0000-0000-000000000310");
        assertTrue(store.compareAndAppendAudited(key, before, next, actor,
                (connection, identity, scope) -> actor.equals(identity) && key.equals(scope),
                new JdbcEntityListSnapshotAuditWriter()));
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

    @Test
    void realNamedProjectTablesGateBootstrapAndHonorRevokedMembership() throws Exception {
        var fixture = new Fixture();
        UUID manager = UUID.fromString("00000000-0000-0000-0000-000000000310");
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000320");
        UUID otherProject = UUID.fromString("00000000-0000-0000-0000-000000000399");
        UUID role = UUID.fromString("00000000-0000-0000-0000-000000000321");
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        // Minimal real-named table fixture. The authoritative production
        // Flyway chain is separately qualified in PostgreSqlContainerMigrationIT.
        try (Connection c = fixture.source.getConnection();
             var ddl = c.createStatement()) {
            ddl.execute("CREATE TABLE users (id UUID PRIMARY KEY, is_active BOOLEAN NOT NULL)");
            ddl.execute("CREATE TABLE projects (id UUID PRIMARY KEY, "
                    + "owner_id UUID NOT NULL, created_by_id UUID NOT NULL, is_active BOOLEAN NOT NULL)");
            ddl.execute("CREATE TABLE xforms (id UUID PRIMARY KEY, project_id UUID NOT NULL, "
                    + "is_active BOOLEAN NOT NULL)");
            ddl.execute("CREATE TABLE roles (id UUID PRIMARY KEY, name TEXT NOT NULL, "
                    + "is_active BOOLEAN NOT NULL)");
            ddl.execute("CREATE TABLE project_members (id UUID PRIMARY KEY, "
                    + "project_id UUID NOT NULL, user_id UUID NOT NULL, role_id UUID NOT NULL, "
                    + "is_active BOOLEAN NOT NULL)");
            ddl.execute("INSERT INTO users VALUES ('" + manager + "', TRUE), ('" + owner + "', TRUE)");
            ddl.execute("INSERT INTO projects VALUES ('" + PROJECT + "', '" + owner
                    + "', '" + owner + "', TRUE), ('" + otherProject + "', '" + owner
                    + "', '" + owner + "', TRUE)");
            ddl.execute("INSERT INTO xforms VALUES ('" + FORM + "', '" + PROJECT + "', TRUE)");
            ddl.execute("INSERT INTO roles VALUES ('" + role + "', 'PROJECT_MANAGER', TRUE)");
            ddl.execute("INSERT INTO project_members VALUES ('" + UUID.randomUUID()
                    + "', '" + PROJECT + "', '" + manager + "', '" + role + "', TRUE)");
        }
        var transactionalGate = new JdbcEntityListSnapshotPermissionGate();
        var bootstrap = new JdbcEntityListSnapshotBootstrap(fixture.source,
                (actor, requestedKey) -> manager.equals(actor) && key.equals(requestedKey));
        var metadata = Map.<String, Object>of("odkMediaType", "entityList");

        try (Connection c = fixture.source.getConnection();
             var statement = c.createStatement()) {
            statement.executeUpdate("UPDATE project_members SET is_active=FALSE WHERE user_id='"
                    + manager + "'");
        }
        assertEquals(JdbcEntityListSnapshotBootstrap.Outcome.DENIED,
                bootstrap.initializeWithTransactionAuthorization(
                        manager, key, metadata, ORIGINAL, md5(ORIGINAL), 1L, transactionalGate));
        assertEquals(0, fixture.historyCount());

        try (Connection c = fixture.source.getConnection();
             var statement = c.createStatement()) {
            statement.executeUpdate("UPDATE project_members SET is_active=TRUE WHERE user_id='"
                    + manager + "'");
            statement.executeUpdate("UPDATE roles SET is_active=FALSE WHERE id='" + role + "'");
        }
        assertEquals(JdbcEntityListSnapshotBootstrap.Outcome.DENIED,
                bootstrap.initializeWithTransactionAuthorization(
                        manager, key, metadata, ORIGINAL, md5(ORIGINAL), 1L, transactionalGate));
        assertEquals(0, fixture.historyCount());

        try (Connection c = fixture.source.getConnection();
             var statement = c.createStatement()) {
            statement.executeUpdate("UPDATE roles SET is_active=TRUE WHERE id='" + role + "'");
            statement.executeUpdate("UPDATE xforms SET is_active=FALSE WHERE id='" + FORM + "'");
        }
        assertEquals(JdbcEntityListSnapshotBootstrap.Outcome.DENIED,
                bootstrap.initializeWithTransactionAuthorization(
                        manager, key, metadata, ORIGINAL, md5(ORIGINAL), 1L, transactionalGate));

        try (Connection c = fixture.source.getConnection();
             var statement = c.createStatement()) {
            statement.executeUpdate("UPDATE xforms SET is_active=TRUE WHERE id='" + FORM + "'");
            statement.executeUpdate("UPDATE users SET is_active=FALSE WHERE id='" + manager + "'");
        }
        assertEquals(JdbcEntityListSnapshotBootstrap.Outcome.DENIED,
                bootstrap.initializeWithTransactionAuthorization(
                        manager, key, metadata, ORIGINAL, md5(ORIGINAL), 1L, transactionalGate));
        try (Connection c = fixture.source.getConnection();
             var statement = c.createStatement()) {
            statement.executeUpdate("UPDATE users SET is_active=TRUE WHERE id='" + manager + "'");
        }
        assertEquals(JdbcEntityListSnapshotBootstrap.Outcome.INITIALIZED,
                bootstrap.initializeWithTransactionAuthorization(
                        manager, key, metadata, ORIGINAL, md5(ORIGINAL), 1L, transactionalGate));
        assertEquals(1, fixture.historyCount());

        // Even an authorized owner cannot bootstrap the form under another project.
        var foreignKey = new EntityListSnapshotLedgerCoordinator.Key(otherProject, FORM, "other.csv");
        try (Connection c = fixture.source.getConnection()) {
            c.setAutoCommit(false);
            assertFalse(transactionalGate.mayInitialize(c, owner, foreignKey));
            c.rollback();
        }
    }

    @Test
    void realNamedTableGateRejectsAutoCommitAndInactiveProject() throws Exception {
        var fixture = new Fixture();
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000320");
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        try (Connection c = fixture.source.getConnection();
             var ddl = c.createStatement()) {
            ddl.execute("CREATE TABLE users (id UUID PRIMARY KEY, is_active BOOLEAN NOT NULL)");
            ddl.execute("CREATE TABLE projects (id UUID PRIMARY KEY, "
                    + "owner_id UUID NOT NULL, created_by_id UUID NOT NULL, is_active BOOLEAN NOT NULL)");
            ddl.execute("CREATE TABLE xforms (id UUID PRIMARY KEY, project_id UUID NOT NULL, "
                    + "is_active BOOLEAN NOT NULL)");
            ddl.execute("INSERT INTO users VALUES ('" + owner + "', TRUE)");
            ddl.execute("INSERT INTO projects VALUES ('" + PROJECT + "', '" + owner
                    + "', '" + owner + "', FALSE)");
            ddl.execute("INSERT INTO xforms VALUES ('" + FORM + "', '" + PROJECT + "', TRUE)");
        }
        var gate = new JdbcEntityListSnapshotPermissionGate();
        try (Connection c = fixture.source.getConnection()) {
            assertFalse(gate.mayInitialize(c, owner, key));
            c.setAutoCommit(false);
            assertFalse(gate.mayInitialize(c, owner, key));
            c.rollback();
        }
    }

    @Test
    void auditedInitializationCommitsExactlyOneImmutableActorEvent() throws Exception {
        var fixture = new Fixture();
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000310");
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var bootstrap = new JdbcEntityListSnapshotBootstrap(fixture.source,
                (actor, target) -> owner.equals(actor) && key.equals(target));
        var alwaysAuthorized = (JdbcEntityListSnapshotBootstrap.TransactionAuthorization)
                (connection, actor, target) -> owner.equals(actor) && key.equals(target);
        var audit = new JdbcEntityListSnapshotAuditWriter();
        var meta = Map.<String, Object>of("odkMediaType", "entityList");

        assertEquals(JdbcEntityListSnapshotBootstrap.Outcome.DENIED,
                bootstrap.initializeWithTransactionAudit(
                        owner, key, meta, ORIGINAL, md5(ORIGINAL), 1L,
                        (connection, actor, target) -> false, audit));
        assertEquals(0, fixture.historyCount());

        assertEquals(JdbcEntityListSnapshotBootstrap.Outcome.INITIALIZED,
                bootstrap.initializeWithTransactionAudit(
                        owner, key, meta, ORIGINAL, md5(ORIGINAL), 1L,
                        alwaysAuthorized, audit));
        assertEquals(JdbcEntityListSnapshotBootstrap.Outcome.ALREADY_INITIALIZED,
                bootstrap.initializeWithTransactionAudit(
                        owner, key, meta, ORIGINAL, md5(ORIGINAL), 1L,
                        alwaysAuthorized, audit));

        try (Connection connection = fixture.source.getConnection();
             var query = connection.prepareStatement(
                     "SELECT actor_id,revision,sha256,event_type FROM entity_list_snapshot_audit");
             var rows = query.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(owner, rows.getObject("actor_id", UUID.class));
            assertEquals(1L, rows.getLong("revision"));
            assertEquals(fixture.snapshot(1L, ORIGINAL).sha256(), rows.getString("sha256"));
            assertEquals("INITIALIZED", rows.getString("event_type"));
            assertFalse(rows.next());
        }
        try (Connection connection = fixture.source.getConnection();
             var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () ->
                    statement.executeUpdate("DELETE FROM entity_list_snapshot_audit"));
            assertThrows(SQLException.class, () ->
                    statement.executeUpdate(
                            "UPDATE entity_list_snapshot_audit SET event_type='INITIALIZED'"));
        }
        assertEquals(1, fixture.historyCount());
    }

    @Test
    void failedAuditInsertRollsBackSnapshotHistoryAndHead() throws Exception {
        var fixture = new Fixture();
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000310");
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var bootstrap = new JdbcEntityListSnapshotBootstrap(fixture.source,
                (actor, target) -> owner.equals(actor) && key.equals(target));
        // Simulate missing / unavailable audit storage while head and history
        // exist. Failure must prevent partial commit.
        try (Connection connection = fixture.source.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE entity_list_snapshot_audit");
        }
        assertThrows(IllegalStateException.class, () ->
                bootstrap.initializeWithTransactionAudit(
                        owner, key, Map.of("odkMediaType", "entityList"),
                        ORIGINAL, md5(ORIGINAL), 1L,
                        (connection, actor, target) -> true,
                        new JdbcEntityListSnapshotAuditWriter()));
        assertEquals(0, fixture.historyCount());
        try (Connection connection = fixture.source.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM entity_list_snapshot_heads")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void auditedRevisionAppendCreatesOneImmutableActorEventAndRejectsStaleWrites() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var store = new JdbcEntityListSnapshotStore(fixture.source);
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        UUID actor = UUID.fromString("00000000-0000-0000-0000-000000000310");
        var first = store.currentHead(key).orElseThrow();
        var secondEvidence = fixture.snapshot(2L, SECOND);
        var second = new EntityListSnapshotLedgerCoordinator.Head(secondEvidence, SECOND);
        var audit = new JdbcEntityListSnapshotAuditWriter();
        var allowed = (JdbcEntityListSnapshotBootstrap.TransactionAuthorization)
                (connection, identity, requestedKey) ->
                        actor.equals(identity) && key.equals(requestedKey);

        assertFalse(store.compareAndAppendAudited(key, first, second, actor,
                (connection, identity, requestedKey) -> false, audit));
        assertEquals(1, fixture.historyCount());
        assertTrue(store.compareAndAppendAudited(key, first, second, actor, allowed, audit));
        assertFalse(store.compareAndAppendAudited(key, first, second, actor, allowed, audit));
        assertEquals(2, fixture.historyCount());
        assertEquals(2L, store.currentHead(key).orElseThrow().evidence().revision());

        try (Connection connection = fixture.source.getConnection();
             var query = connection.prepareStatement(
                     "SELECT actor_id,revision,sha256,event_type FROM entity_list_snapshot_audit");
             var rows = query.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(actor, rows.getObject("actor_id", UUID.class));
            assertEquals(2L, rows.getLong("revision"));
            assertEquals(secondEvidence.sha256(), rows.getString("sha256"));
            assertEquals("APPENDED", rows.getString("event_type"));
            assertFalse(rows.next());
        }
        try (Connection connection = fixture.source.getConnection();
             var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () ->
                    statement.executeUpdate("DELETE FROM entity_list_snapshot_audit"));
        }
    }

    @Test
    void failedAppendAuditRollsBackRevisionHistoryAndHead() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var store = new JdbcEntityListSnapshotStore(fixture.source);
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var first = store.currentHead(key).orElseThrow();
        var second = new EntityListSnapshotLedgerCoordinator.Head(
                fixture.snapshot(2L, SECOND), SECOND);
        UUID actor = UUID.fromString("00000000-0000-0000-0000-000000000310");
        try (Connection connection = fixture.source.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE entity_list_snapshot_audit");
        }
        assertThrows(IllegalStateException.class, () ->
                store.compareAndAppendAudited(key, first, second, actor,
                        (connection, identity, requestedKey) -> true,
                        new JdbcEntityListSnapshotAuditWriter()));
        assertEquals(1, fixture.historyCount());
        var unchanged = store.currentHead(key).orElseThrow();
        assertEquals(1L, unchanged.evidence().revision());
        assertTrue(Arrays.equals(ORIGINAL, unchanged.bytes()));
    }

    @Test
    void auditedRevisionFacadeNeverUsesUnauditedStoreWrite() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        UUID actorId = UUID.fromString("00000000-0000-0000-0000-000000000310");
        var user = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
        org.mockito.Mockito.when(user.getId()).thenReturn(actorId);
        org.mockito.Mockito.when(user.getIsActive()).thenReturn(true);
        var currentUsers = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        org.mockito.Mockito.when(currentUsers.getCurrentWebUser())
                .thenReturn(java.util.Optional.of(user));
        var webPolicy = org.mockito.Mockito.mock(EntityListSnapshotWebAuthorization.class);
        org.mockito.Mockito.when(webPolicy.mayInitialize(actorId, key)).thenReturn(true);
        var transactionPolicy = org.mockito.Mockito.mock(
                JdbcEntityListSnapshotPermissionGate.class);
        org.mockito.Mockito.when(transactionPolicy.mayInitialize(
                org.mockito.ArgumentMatchers.any(Connection.class),
                org.mockito.ArgumentMatchers.eq(actorId),
                org.mockito.ArgumentMatchers.eq(key))).thenReturn(true);
        var spyStore = org.mockito.Mockito.spy(new JdbcEntityListSnapshotStore(fixture.source));
        var audited = new EntityListAuditedRevisionService(
                spyStore, currentUsers, webPolicy,
                transactionPolicy, new JdbcEntityListSnapshotAuditWriter());

        assertEquals(EntityListSnapshotLedgerCoordinator.Outcome.APPENDED,
                audited.propose(key, Map.of("odkMediaType", "entityList"),
                        SECOND, md5(SECOND), 1L).outcome());
        assertEquals(EntityListSnapshotLedgerCoordinator.Outcome.UNCHANGED,
                audited.propose(key, Map.of("odkMediaType", "entityList"),
                        SECOND, md5(SECOND), 1L).outcome());
        org.mockito.Mockito.verify(spyStore, org.mockito.Mockito.times(1))
                .compareAndAppendAudited(
                        org.mockito.ArgumentMatchers.eq(key),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(actorId),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(spyStore, org.mockito.Mockito.never())
                .compareAndAppend(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        assertEquals(2, fixture.historyCount());
        try (Connection c = fixture.source.getConnection();
             var query = c.prepareStatement(
                     "SELECT actor_id, event_type FROM entity_list_snapshot_audit");
             var rows = query.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(actorId, rows.getObject("actor_id", UUID.class));
            assertEquals("APPENDED", rows.getString("event_type"));
            assertFalse(rows.next());
        }
    }

    @Test
    void auditedRevisionFacadeFailsClosedWithoutWebPrincipalOrManagerPermission()
            throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        UUID actorId = UUID.fromString("00000000-0000-0000-0000-000000000310");
        var identity = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var policy = org.mockito.Mockito.mock(EntityListSnapshotWebAuthorization.class);
        var gate = org.mockito.Mockito.mock(JdbcEntityListSnapshotPermissionGate.class);
        var store = org.mockito.Mockito.spy(new JdbcEntityListSnapshotStore(fixture.source));
        var audited = new EntityListAuditedRevisionService(
                store, identity, policy, gate, new JdbcEntityListSnapshotAuditWriter());
        assertEquals(EntityListSnapshotLedgerCoordinator.Outcome.INVALID_OR_NOT_INITIALIZED,
                audited.propose(key, Map.of("odkMediaType", "entityList"),
                        SECOND, md5(SECOND), 1L).outcome());
        var user = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
        org.mockito.Mockito.when(user.getId()).thenReturn(actorId);
        org.mockito.Mockito.when(user.getIsActive()).thenReturn(true);
        org.mockito.Mockito.when(identity.getCurrentWebUser())
                .thenReturn(java.util.Optional.of(user));
        // Manager policy defaults to false: no successful publication.
        assertEquals(EntityListSnapshotLedgerCoordinator.Outcome.INVALID_OR_NOT_INITIALIZED,
                audited.propose(key, Map.of("odkMediaType", "entityList"),
                        SECOND, md5(SECOND), 1L).outcome());
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
                .compareAndAppendAudited(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        assertEquals(1, fixture.historyCount());
    }

    @Test
    void digestMismatchedHeadFailsClosedInsteadOfLookingUninitialized() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var store = new JdbcEntityListSnapshotStore(fixture.source);
        assertEquals(1L, store.currentHead(key).orElseThrow().evidence().revision());

        // The isolated candidate FK validates revision existence but does
        // not include SHA-256, so a damaged head digest can otherwise pass
        // database constraints. This must never look like "not initialized".
        try (Connection connection = fixture.source.getConnection();
             var sql = connection.prepareStatement(
                     "UPDATE entity_list_snapshot_heads SET sha256=? "
                             + "WHERE project_id=? AND form_id=? AND filename=?")) {
            sql.setString(1, "a".repeat(64));
            sql.setObject(2, PROJECT);
            sql.setObject(3, FORM);
            sql.setString(4, FILENAME);
            assertEquals(1, sql.executeUpdate());
        }
        assertThrows(IllegalStateException.class, () -> store.currentHead(key));
        assertThrows(IllegalStateException.class, () ->
                EntityListSnapshotLedgerCoordinator.propose(
                        store, key, Map.of("odkMediaType", "entityList"),
                        SECOND, md5(SECOND), 1L));
        assertEquals(1, fixture.historyCount());
    }

    @Test
    void absentHeadRemainsDistinctFromCorruptHead() throws Exception {
        var fixture = new Fixture();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        assertTrue(new JdbcEntityListSnapshotStore(fixture.source)
                .currentHead(key).isEmpty());
    }

}
