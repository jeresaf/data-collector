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

        // Simulate a legacy/broken deployment WITHOUT the new exact-digest
        // constraint: the JDBC reader must still detect inconsistent history.
        try (Connection connection = fixture.source.getConnection();
             var ddl = connection.createStatement()) {
            ddl.execute("ALTER TABLE entity_list_snapshot_heads "
                    + "DROP CONSTRAINT fk_entity_list_head_exact_history");
        }
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

    @Test
    void exactHistoryDigestForeignKeysRejectCorruptHeadAndAudit() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var store = new JdbcEntityListSnapshotStore(fixture.source);
        // FK is deferred so the invalid UPDATE appears to succeed until commit.
        try (Connection connection = fixture.source.getConnection()) {
            connection.setAutoCommit(false);
            try (var sql = connection.prepareStatement(
                    "UPDATE entity_list_snapshot_heads SET sha256=? WHERE project_id=? "
                            + "AND form_id=? AND filename=?")) {
                sql.setString(1, "a".repeat(64));
                sql.setObject(2, PROJECT);
                sql.setObject(3, FORM);
                sql.setString(4, FILENAME);
                assertEquals(1, sql.executeUpdate());
            }
            SQLException failure = assertThrows(SQLException.class, connection::commit);
            assertEquals("23503", failure.getSQLState());
            connection.rollback();
        }
        assertEquals(1L, store.currentHead(key).orElseThrow().evidence().revision());

        // Audit is also keyed to the exact recorded digest, not just revision.
        try (Connection connection = fixture.source.getConnection();
             var sql = connection.prepareStatement(
                     "INSERT INTO entity_list_snapshot_audit "
                             + "(actor_id,project_id,form_id,filename,revision,sha256,event_type) "
                             + "VALUES (?,?,?,?,?,?,?)")) {
            sql.setObject(1, UUID.fromString("00000000-0000-0000-0000-000000000310"));
            sql.setObject(2, PROJECT);
            sql.setObject(3, FORM);
            sql.setString(4, FILENAME);
            sql.setLong(5, 1L);
            sql.setString(6, "a".repeat(64));
            sql.setString(7, "INITIALIZED");
            SQLException failure = assertThrows(SQLException.class, sql::executeUpdate);
            assertEquals("23503", failure.getSQLState());
        }
        assertEquals(1, fixture.historyCount());
    }

    @Test
    void persistentRemovalReviewRequiresIndependentFinalDecisionAndKeepsLedgerUnchanged()
            throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var store = new JdbcEntityListSnapshotStore(fixture.source);
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var before = store.currentHead(key).orElseThrow();
        var replacement = ("name,label,__version\n"
                + "f4f8c5db-8333-4449-b13f-720a8ce47071,Replacement,1\n")
                .getBytes(StandardCharsets.UTF_8);
        var candidate = new EntityListSnapshotLedgerCoordinator.Head(
                fixture.snapshot(2L, replacement), replacement);
        UUID requester = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        var evidence = EntityListRemovalReviewRequest.prepare(
                requester, "Removal requires independent approval",
                before, candidate).orElseThrow();
        var register = new JdbcEntityListRemovalReviewRegister(fixture.source);
        assertEquals(reviewId, register.submit(reviewId, evidence));
        var pending = register.get(reviewId);
        assertEquals("PENDING", pending.state());
        assertEquals(before.evidence().sha256(), pending.expectedSha256());
        assertEquals(candidate.evidence().sha256(), pending.candidateSha256());
        assertEquals(evidence.missingIds(), pending.missingIds());
        assertFalse(register.decide(reviewId, requester,
                JdbcEntityListRemovalReviewRegister.Decision.APPROVED));
        assertEquals(JdbcEntityListRemovalReviewRegister.DecisionOutcome.SELF_ACTION,
                register.decideAuthorizedOutcome(reviewId, requester,
                        JdbcEntityListRemovalReviewRegister.Decision.APPROVED,
                        (connection, actor, scope) -> true));
        assertEquals(JdbcEntityListRemovalReviewRegister.DecisionOutcome.PERMISSION_DENIED,
                register.decideAuthorizedOutcome(reviewId, reviewer,
                        JdbcEntityListRemovalReviewRegister.Decision.APPROVED,
                        (connection, actor, scope) -> false));
        assertEquals("PENDING", register.get(reviewId).state());
        assertTrue(register.decideAuthorized(reviewId, reviewer,
                JdbcEntityListRemovalReviewRegister.Decision.APPROVED,
                (connection, actor, scope) -> reviewer.equals(actor) && key.equals(scope)));
        assertEquals(JdbcEntityListRemovalReviewRegister.DecisionOutcome.INVALID_STATE,
                register.decideAuthorizedOutcome(reviewId, UUID.randomUUID(),
                        JdbcEntityListRemovalReviewRegister.Decision.REJECTED,
                        (connection, actor, scope) -> true));
        assertEquals("APPROVED", register.get(reviewId).state());
        assertEquals(reviewer, register.get(reviewId).reviewer());

        // APPROVED is an inert review state. There is NO approved deletion
        // execution path; the ledger's removal policy must remain fail-closed.
        assertEquals(1, fixture.historyCount());
        assertEquals(1L, store.currentHead(key).orElseThrow().evidence().revision());
        assertFalse(store.compareAndAppendAudited(
                key, before, candidate, requester,
                (connection, actor, scope) -> true,
                new JdbcEntityListSnapshotAuditWriter()));
        assertEquals(1, fixture.historyCount());

        try (Connection c = fixture.source.getConnection();
             var sql = c.createStatement()) {
            assertThrows(SQLException.class, () ->
                    sql.executeUpdate("DELETE FROM entity_list_removal_reviews"));
            assertThrows(SQLException.class, () ->
                    sql.executeUpdate("UPDATE entity_list_removal_reviews "
                            + "SET justification='changed'"));
            assertThrows(SQLException.class, () ->
                    sql.executeUpdate("UPDATE entity_list_removal_reviews SET state='REJECTED'"));
        }
    }

    @Test
    void concurrentReviewDecisionsHaveExactlyOneWinnerAndNoLedgerAppend()
            throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var before = new JdbcEntityListSnapshotStore(fixture.source)
                .currentHead(new EntityListSnapshotLedgerCoordinator.Key(
                        PROJECT, FORM, FILENAME)).orElseThrow();
        var replacement = ("name,label,__version\n"
                + "f4f8c5db-8333-4449-b13f-720a8ce47071,Replacement,1\n")
                .getBytes(StandardCharsets.UTF_8);
        var candidate = new EntityListSnapshotLedgerCoordinator.Head(
                fixture.snapshot(2L, replacement), replacement);
        UUID requester = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        var register = new JdbcEntityListRemovalReviewRegister(fixture.source);
        register.submit(reviewId, EntityListRemovalReviewRequest.prepare(
                requester, "Removal pending", before, candidate).orElseThrow());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var approval = pool.submit(() -> {
                start.await();
                return register.decideAuthorized(reviewId, UUID.randomUUID(),
                        JdbcEntityListRemovalReviewRegister.Decision.APPROVED,
                        (connection, actor, scope) -> true);
            });
            var rejection = pool.submit(() -> {
                start.await();
                return register.decideAuthorized(reviewId, UUID.randomUUID(),
                        JdbcEntityListRemovalReviewRegister.Decision.REJECTED,
                        (connection, actor, scope) -> true);
            });
            start.countDown();
            boolean a = approval.get(15, java.util.concurrent.TimeUnit.SECONDS);
            boolean b = rejection.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(a ^ b);
            assertEquals(a ? "APPROVED" : "REJECTED", register.get(reviewId).state());
            assertEquals(1, fixture.historyCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void authenticatedReviewerFacadeRequiresWebPrincipalAndTransactionalPrivilege()
            throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var before = new JdbcEntityListSnapshotStore(fixture.source).currentHead(key).orElseThrow();
        var candidateBytes = ("name,label,__version\n"
                + "f4f8c5db-8333-4449-b13f-720a8ce47071,Replacement,1\n")
                .getBytes(StandardCharsets.UTF_8);
        var candidate = new EntityListSnapshotLedgerCoordinator.Head(
                fixture.snapshot(2L, candidateBytes), candidateBytes);
        UUID requester = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        var register = new JdbcEntityListRemovalReviewRegister(fixture.source);
        register.submit(id, EntityListRemovalReviewRequest.prepare(
                requester, "Require another reviewer",
                before, candidate).orElseThrow());

        var identity = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var policy = org.mockito.Mockito.mock(EntityListSnapshotWebAuthorization.class);
        var gate = org.mockito.Mockito.mock(JdbcEntityListSnapshotPermissionGate.class);
        var service = new EntityListAuthenticatedReviewService(
                identity, policy, gate, register,
                new JdbcEntityListDenialEvidenceWriter(fixture.source));
        assertFalse(service.decide(id, JdbcEntityListRemovalReviewRegister.Decision.APPROVED));
        assertEquals("PENDING", register.get(id).state());

        var user = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
        org.mockito.Mockito.when(user.getId()).thenReturn(reviewer);
        org.mockito.Mockito.when(user.getIsActive()).thenReturn(true);
        org.mockito.Mockito.when(identity.getCurrentWebUser())
                .thenReturn(java.util.Optional.of(user));
        assertFalse(service.decide(id, JdbcEntityListRemovalReviewRegister.Decision.APPROVED));
        assertEquals("PENDING", register.get(id).state());

        org.mockito.Mockito.when(policy.mayInitialize(reviewer, key)).thenReturn(true);
        assertFalse(service.decide(id, JdbcEntityListRemovalReviewRegister.Decision.APPROVED));
        assertEquals("PENDING", register.get(id).state());
        try (Connection auditConnection = fixture.source.getConnection();
             var auditQuery = auditConnection.prepareStatement(
                     "SELECT reason FROM entity_list_denial_evidence "
                             + "WHERE review_id=? AND operation='REVIEW_DECISION' "
                             + "ORDER BY occurred_at DESC LIMIT 1")) {
            auditQuery.setObject(1, id);
            try (var event = auditQuery.executeQuery()) {
                assertTrue(event.next());
                assertEquals("PERMISSION_DENIED", event.getString("reason"));
            }
        }

        org.mockito.Mockito.when(gate.mayInitialize(
                org.mockito.ArgumentMatchers.any(Connection.class),
                org.mockito.ArgumentMatchers.eq(reviewer),
                org.mockito.ArgumentMatchers.eq(key))).thenReturn(true);
        assertTrue(service.decide(id, JdbcEntityListRemovalReviewRegister.Decision.APPROVED));
        assertEquals("APPROVED", register.get(id).state());
        assertEquals(reviewer, register.get(id).reviewer());
        assertEquals(1, fixture.historyCount());
        assertFalse(service.decide(id, JdbcEntityListRemovalReviewRegister.Decision.REJECTED));
        try (Connection c = fixture.source.getConnection();
             var statement = c.prepareStatement(
                     "SELECT COUNT(*) FROM entity_list_denial_evidence "
                             + "WHERE review_id=? AND operation='REVIEW_DECISION'")) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(4L, rows.getLong(1));
            }
        }
    }

    @Test
    void approvedRemovalCanBeValidatedOnceWithoutAnyLedgerPublication() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var store = new JdbcEntityListSnapshotStore(fixture.source);
        var original = store.currentHead(key).orElseThrow();
        var bytes = ("name,label,__version\n"
                + "f4f8c5db-8333-4449-b13f-720a8ce47071,Replacement,1\n")
                .getBytes(StandardCharsets.UTF_8);
        var candidate = new EntityListSnapshotLedgerCoordinator.Head(
                fixture.snapshot(2L, bytes), bytes);
        UUID requester = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        UUID executor = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        var register = new JdbcEntityListRemovalReviewRegister(fixture.source);
        register.submit(reviewId, EntityListRemovalReviewRequest.prepare(
                requester, "Removal held for independent review",
                original, candidate).orElseThrow());
        var sealing = new JdbcEntityListRemovalApprovalSeal(fixture.source);
        var authorized = (JdbcEntityListSnapshotBootstrap.TransactionAuthorization)
                (connection, actor, scope) -> executor.equals(actor) && key.equals(scope);

        // Client-supplied UUIDs must NEVER mint a readiness receipt.
        assertFalse(sealing.validateAndSeal(reviewId, executor, candidate, authorized));
        assertFalse(sealing.validateAndSealAuthorized(reviewId, executor, candidate, authorized));
        assertTrue(register.decideAuthorized(reviewId, reviewer,
                JdbcEntityListRemovalReviewRegister.Decision.APPROVED,
                (connection, actor, scope) -> key.equals(scope)));

        assertFalse(sealing.validateAndSealAuthorized(reviewId, requester, candidate,
                (connection, actor, scope) -> true));
        assertFalse(sealing.validateAndSealAuthorized(reviewId, reviewer, candidate,
                (connection, actor, scope) -> true));
        assertFalse(sealing.validateAndSealAuthorized(reviewId, executor, candidate,
                (connection, actor, scope) -> false));

        var forgedBytes = ("name,label,__version\n"
                + "f4f8c5db-8333-4449-b13f-720a8ce47071,Tampered,1\n")
                .getBytes(StandardCharsets.UTF_8);
        var forged = new EntityListSnapshotLedgerCoordinator.Head(
                candidate.evidence(), forgedBytes);
        assertFalse(sealing.validateAndSealAuthorized(reviewId, executor, forged, authorized));

        assertTrue(sealing.validateAndSealAuthorized(reviewId, executor, candidate, authorized));
        assertEquals(JdbcEntityListRemovalApprovalSeal.ValidationOutcome.REPLAY,
                sealing.validateAndSealAuthorizedOutcome(
                        reviewId, executor, candidate, authorized));
        try (Connection c = fixture.source.getConnection();
             var q = c.createStatement();
             var rows = q.executeQuery(
                     "SELECT review_id,validated_by FROM entity_list_removal_validation_seals")) {
            assertTrue(rows.next());
            assertEquals(reviewId, rows.getObject("review_id", UUID.class));
            assertEquals(executor, rows.getObject("validated_by", UUID.class));
            assertFalse(rows.next());
        }
        assertEquals(1, fixture.historyCount());
        assertEquals(1L, store.currentHead(key).orElseThrow().evidence().revision());
        try (Connection c = fixture.source.getConnection();
             var stmt = c.createStatement()) {
            assertThrows(SQLException.class, () ->
                    stmt.executeUpdate("DELETE FROM entity_list_removal_validation_seals"));
        }
    }

    @Test
    void staleApprovedRemovalCannotBeSealedAfterHeadAdvances() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var store = new JdbcEntityListSnapshotStore(fixture.source);
        var original = store.currentHead(key).orElseThrow();
        var removedBytes = ("name,label,__version\n"
                + "f4f8c5db-8333-4449-b13f-720a8ce47071,Replacement,1\n")
                .getBytes(StandardCharsets.UTF_8);
        var candidate = new EntityListSnapshotLedgerCoordinator.Head(
                fixture.snapshot(2L, removedBytes), removedBytes);
        var requestor = UUID.randomUUID();
        var reviewer = UUID.randomUUID();
        var executor = UUID.randomUUID();
        var reviewId = UUID.randomUUID();
        var register = new JdbcEntityListRemovalReviewRegister(fixture.source);
        register.submit(reviewId, EntityListRemovalReviewRequest.prepare(
                requestor, "Must be stale after safe head update",
                original, candidate).orElseThrow());
        assertTrue(register.decideAuthorized(reviewId, reviewer,
                JdbcEntityListRemovalReviewRegister.Decision.APPROVED,
                (connection, actor, scope) -> true));
        // A legitimate non-removal update advances the exact snapshot head
        // before the approved removal is validated.
        var safeUpdate = new EntityListSnapshotLedgerCoordinator.Head(
                fixture.snapshot(2L, SECOND), SECOND);
        assertTrue(store.compareAndAppendAudited(key, original, safeUpdate, executor,
                (connection, actor, scope) -> true,
                new JdbcEntityListSnapshotAuditWriter()));
        var sealing = new JdbcEntityListRemovalApprovalSeal(fixture.source);
        assertEquals(JdbcEntityListRemovalApprovalSeal.ValidationOutcome.STALE_HEAD,
                sealing.validateAndSealAuthorizedOutcome(
                        reviewId, executor, candidate,
                        (connection, actor, scope) -> true));
        assertEquals(2L, store.currentHead(key).orElseThrow().evidence().revision());
        try (Connection c = fixture.source.getConnection();
             var q = c.createStatement();
             var rows = q.executeQuery("SELECT COUNT(*) FROM entity_list_removal_validation_seals")) {
            assertTrue(rows.next());
            assertEquals(0L, rows.getLong(1));
        }
    }

    @Test
    void principalBoundExecutorCannotBeSpoofedOrSelfExecuteAndNeedsTransactionalPermission()
            throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var before = new JdbcEntityListSnapshotStore(fixture.source)
                .currentHead(key).orElseThrow();
        var replacement = ("name,label,__version\n"
                + "f4f8c5db-8333-4449-b13f-720a8ce47071,Replacement,1\n")
                .getBytes(StandardCharsets.UTF_8);
        var candidate = new EntityListSnapshotLedgerCoordinator.Head(
                fixture.snapshot(2L, replacement), replacement);
        UUID requester = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        UUID executor = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        var register = new JdbcEntityListRemovalReviewRegister(fixture.source);
        register.submit(reviewId, EntityListRemovalReviewRequest.prepare(
                requester, "Executor must be independently authorized",
                before, candidate).orElseThrow());
        assertTrue(register.decideAuthorized(reviewId, reviewer,
                JdbcEntityListRemovalReviewRegister.Decision.APPROVED,
                (connection, actor, scope) -> true));

        var identity = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var policy = org.mockito.Mockito.mock(EntityListSnapshotWebAuthorization.class);
        var gate = org.mockito.Mockito.mock(JdbcEntityListSnapshotPermissionGate.class);
        var seal = new JdbcEntityListRemovalApprovalSeal(fixture.source);
        var facade = new EntityListAuthenticatedSealService(
                identity, policy, gate, register, seal,
                new JdbcEntityListDenialEvidenceWriter(fixture.source));
        assertFalse(facade.validateApprovedReview(reviewId, candidate));

        var user = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
        org.mockito.Mockito.when(user.getId()).thenReturn(requester, reviewer, executor);
        org.mockito.Mockito.when(user.getIsActive()).thenReturn(true);
        org.mockito.Mockito.when(identity.getCurrentWebUser())
                .thenReturn(java.util.Optional.of(user));
        // Neither the requester nor the deciding reviewer can execute.
        assertFalse(facade.validateApprovedReview(reviewId, candidate));
        assertFalse(facade.validateApprovedReview(reviewId, candidate));
        assertFalse(facade.validateApprovedReview(reviewId, candidate));
        org.mockito.Mockito.when(policy.mayInitialize(executor, key)).thenReturn(true);
        // Web preflight is not enough without a same-transaction permission.
        assertFalse(facade.validateApprovedReview(reviewId, candidate));
        org.mockito.Mockito.when(gate.mayInitialize(
                org.mockito.ArgumentMatchers.any(Connection.class),
                org.mockito.ArgumentMatchers.eq(executor),
                org.mockito.ArgumentMatchers.eq(key))).thenReturn(true);
        assertTrue(facade.validateApprovedReview(reviewId, candidate));
        assertFalse(facade.validateApprovedReview(reviewId, candidate));
        try (Connection c = fixture.source.getConnection();
             var q = c.createStatement();
             var rows = q.executeQuery(
                     "SELECT validated_by FROM entity_list_removal_validation_seals")) {
            assertTrue(rows.next());
            assertEquals(executor, rows.getObject(1, UUID.class));
            assertFalse(rows.next());
        }
        assertEquals(1, fixture.historyCount());
        assertEquals(1L, new JdbcEntityListSnapshotStore(fixture.source)
                .currentHead(key).orElseThrow().evidence().revision());
        try (Connection c = fixture.source.getConnection();
             var statement = c.prepareStatement(
                     "SELECT COUNT(*) FROM entity_list_denial_evidence "
                             + "WHERE review_id=? AND operation='APPROVAL_VALIDATION'")) {
            statement.setObject(1, reviewId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(6L, rows.getLong(1));
            }
        }
        try (Connection c = fixture.source.getConnection();
             var statement = c.prepareStatement(
                     "SELECT COUNT(*) FROM entity_list_denial_evidence "
                             + "WHERE review_id=? AND reason='REPLAY'")) {
            statement.setObject(1, reviewId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1L, rows.getLong(1));
            }
        }
    }

    @Test
    void denialJournalSurvivesRejectedTransactionRollbackAndIsImmutable() throws Exception {
        var fixture = new Fixture();
        UUID reviewId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        var writer = new JdbcEntityListDenialEvidenceWriter(fixture.source);

        try (Connection rejectedAction = fixture.source.getConnection()) {
            rejectedAction.setAutoCommit(false);
            // The rejected review transaction and the security journal MUST
            // use distinct connections: rolling this back must not remove
            // an already committed denial event.
            UUID firstEvent = writer.record(
                    reviewId, actor,
                    JdbcEntityListDenialEvidenceWriter.Operation.REVIEW_DECISION,
                    JdbcEntityListDenialEvidenceWriter.Reason.PERMISSION_DENIED);
            rejectedAction.rollback();

            UUID anonymousEvent = writer.record(
                    reviewId, null,
                    JdbcEntityListDenialEvidenceWriter.Operation.APPROVAL_VALIDATION,
                    JdbcEntityListDenialEvidenceWriter.Reason.UNAUTHENTICATED);
            assertFalse(firstEvent.equals(anonymousEvent));
        }

        try (Connection connection = fixture.source.getConnection();
             var query = connection.prepareStatement(
                     "SELECT operation,reason,actor_id FROM entity_list_denial_evidence "
                             + "WHERE review_id=? ORDER BY occurred_at,id")) {
            query.setObject(1, reviewId);
            try (var rows = query.executeQuery()) {
                int count = 0;
                boolean foundAuthenticated = false;
                boolean foundAnonymous = false;
                while (rows.next()) {
                    count++;
                    if (actor.equals(rows.getObject("actor_id", UUID.class))) {
                        assertEquals("REVIEW_DECISION", rows.getString("operation"));
                        assertEquals("PERMISSION_DENIED", rows.getString("reason"));
                        foundAuthenticated = true;
                    } else {
                        assertEquals(null, rows.getObject("actor_id"));
                        assertEquals("APPROVAL_VALIDATION", rows.getString("operation"));
                        assertEquals("UNAUTHENTICATED", rows.getString("reason"));
                        foundAnonymous = true;
                    }
                }
                assertEquals(2, count);
                assertTrue(foundAuthenticated);
                assertTrue(foundAnonymous);
            }
        }
        try (Connection connection = fixture.source.getConnection();
             var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE entity_list_denial_evidence SET reason='REPLAY'"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "DELETE FROM entity_list_denial_evidence"));
        }
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void denialJournalFailsClosedWhenStorageUnavailable() throws Exception {
        var fixture = new Fixture();
        UUID reviewId = UUID.randomUUID();
        try (Connection connection = fixture.source.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE entity_list_denial_evidence");
        }
        var writer = new JdbcEntityListDenialEvidenceWriter(fixture.source);
        assertThrows(IllegalStateException.class, () ->
                writer.record(reviewId, UUID.randomUUID(),
                        JdbcEntityListDenialEvidenceWriter.Operation.APPROVAL_VALIDATION,
                        JdbcEntityListDenialEvidenceWriter.Reason.PERMISSION_DENIED));
    }

    @Test
    void authenticatedReviewDenialFailsClosedWhenMandatoryJournalUnavailable() throws Exception {
        var fixture = new Fixture();
        UUID reviewId = UUID.randomUUID();
        var identity = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var facade = new EntityListAuthenticatedReviewService(
                identity,
                org.mockito.Mockito.mock(EntityListSnapshotWebAuthorization.class),
                org.mockito.Mockito.mock(JdbcEntityListSnapshotPermissionGate.class),
                new JdbcEntityListRemovalReviewRegister(fixture.source),
                new JdbcEntityListDenialEvidenceWriter(fixture.source));
        try (Connection c = fixture.source.getConnection();
             var statement = c.createStatement()) {
            statement.execute("DROP TABLE entity_list_denial_evidence");
        }
        assertThrows(IllegalStateException.class, () ->
                facade.decide(reviewId,
                        JdbcEntityListRemovalReviewRegister.Decision.APPROVED));
    }

    @Test
    void authenticatedSealDenialFailsClosedWhenMandatoryJournalUnavailable() throws Exception {
        var fixture = new Fixture();
        var facade = new EntityListAuthenticatedSealService(
                org.mockito.Mockito.mock(org.odk.platform.security.CurrentUserService.class),
                org.mockito.Mockito.mock(EntityListSnapshotWebAuthorization.class),
                org.mockito.Mockito.mock(JdbcEntityListSnapshotPermissionGate.class),
                new JdbcEntityListRemovalReviewRegister(fixture.source),
                new JdbcEntityListRemovalApprovalSeal(fixture.source),
                new JdbcEntityListDenialEvidenceWriter(fixture.source));
        try (Connection c = fixture.source.getConnection();
             var statement = c.createStatement()) {
            statement.execute("DROP TABLE entity_list_denial_evidence");
        }
        assertThrows(IllegalStateException.class, () ->
                facade.validateApprovedReview(UUID.randomUUID(), null));
    }

}
