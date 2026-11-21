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


    private static JdbcEntityListRemovalRoleGate roleGate(JdbcEntityListRemovalRoleGate.Role role) {
        var gate = org.mockito.Mockito.mock(JdbcEntityListRemovalRoleGate.class);
        org.mockito.Mockito.when(gate.role()).thenReturn(role);
        return gate;
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
        var gate = roleGate(JdbcEntityListRemovalRoleGate.Role.REVIEWER);
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
        var gate = roleGate(JdbcEntityListRemovalRoleGate.Role.EXECUTOR);
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
                roleGate(JdbcEntityListRemovalRoleGate.Role.REVIEWER),
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
                roleGate(JdbcEntityListRemovalRoleGate.Role.EXECUTOR),
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

    @Test
    void missingReviewIdIsAuditedByBothFacadesWithoutFabricatingIdentity()
            throws Exception {
        var fixture = new Fixture();
        var writer = new JdbcEntityListDenialEvidenceWriter(fixture.source);
        var identity = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var policy = org.mockito.Mockito.mock(EntityListSnapshotWebAuthorization.class);
        var gate = roleGate(JdbcEntityListRemovalRoleGate.Role.REVIEWER);
        var register = new JdbcEntityListRemovalReviewRegister(fixture.source);
        var reviewer = new EntityListAuthenticatedReviewService(
                identity, policy, gate, register, writer);
        var executor = new EntityListAuthenticatedSealService(
                identity, policy, roleGate(JdbcEntityListRemovalRoleGate.Role.EXECUTOR), register,
                new JdbcEntityListRemovalApprovalSeal(fixture.source), writer);

        assertFalse(reviewer.decide(
                null, JdbcEntityListRemovalReviewRegister.Decision.APPROVED));
        assertFalse(executor.validateApprovedReview(null, null));
        try (Connection c = fixture.source.getConnection();
             var q = c.prepareStatement(
                     "SELECT id, review_id, actor_id, operation, reason "
                             + "FROM entity_list_denial_evidence "
                             + "WHERE review_id IS NULL ORDER BY operation")) {
            try (var rows = q.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("APPROVAL_VALIDATION", rows.getString("operation"));
                assertEquals("INVALID_STATE", rows.getString("reason"));
                assertTrue(rows.getObject("id", UUID.class) != null);
                assertEquals(null, rows.getObject("review_id"));
                assertEquals(null, rows.getObject("actor_id"));
                assertTrue(rows.next());
                assertEquals("REVIEW_DECISION", rows.getString("operation"));
                assertEquals("INVALID_STATE", rows.getString("reason"));
                assertTrue(rows.getObject("id", UUID.class) != null);
                assertEquals(null, rows.getObject("review_id"));
                assertEquals(null, rows.getObject("actor_id"));
                assertFalse(rows.next());
            }
        }
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void missingReviewIdFailsVisiblyWhenRequiredAuditIsUnavailable()
            throws Exception {
        var fixture = new Fixture();
        var identity = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var policy = org.mockito.Mockito.mock(EntityListSnapshotWebAuthorization.class);
        var gate = roleGate(JdbcEntityListRemovalRoleGate.Role.REVIEWER);
        var register = new JdbcEntityListRemovalReviewRegister(fixture.source);
        var writer = new JdbcEntityListDenialEvidenceWriter(fixture.source);
        var reviewer = new EntityListAuthenticatedReviewService(
                identity, policy, gate, register, writer);
        var executor = new EntityListAuthenticatedSealService(
                identity, policy, roleGate(JdbcEntityListRemovalRoleGate.Role.EXECUTOR), register,
                new JdbcEntityListRemovalApprovalSeal(fixture.source), writer);
        try (Connection c = fixture.source.getConnection();
             var ddl = c.createStatement()) {
            ddl.execute("DROP TABLE entity_list_denial_evidence");
        }
        assertThrows(IllegalStateException.class, () ->
                reviewer.decide(null,
                        JdbcEntityListRemovalReviewRegister.Decision.APPROVED));
        assertThrows(IllegalStateException.class, () ->
                executor.validateApprovedReview(null, null));
    }

    @Test
    void unexpectedReviewAndSealFailuresProduceIndependentImmutableAuditEvents()
            throws Exception {
        var fixture = new Fixture();
        UUID id = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        var writer = new JdbcEntityListDenialEvidenceWriter(fixture.source);
        var identity = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var webAuthorization = org.mockito.Mockito.mock(EntityListSnapshotWebAuthorization.class);
        var gate = roleGate(JdbcEntityListRemovalRoleGate.Role.REVIEWER);
        var register = org.mockito.Mockito.mock(JdbcEntityListRemovalReviewRegister.class);
        var seal = org.mockito.Mockito.mock(JdbcEntityListRemovalApprovalSeal.class);
        var webUser = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
        org.mockito.Mockito.when(webUser.getId()).thenReturn(actor);
        org.mockito.Mockito.when(webUser.getIsActive()).thenReturn(true);
        org.mockito.Mockito.when(identity.getCurrentWebUser())
                .thenReturn(java.util.Optional.of(webUser));
        var reviewer = new EntityListAuthenticatedReviewService(
                identity, webAuthorization, gate, register, writer);
        var executor = new EntityListAuthenticatedSealService(
                identity, webAuthorization, roleGate(JdbcEntityListRemovalRoleGate.Role.EXECUTOR),
                register, seal, writer);
        var failure = new IllegalStateException("synthetic confidential database error");
        org.mockito.Mockito.when(register.get(id)).thenThrow(failure);

        // The exception must remain visible, but the journal stores only
        // UNAVAILABLE and never the exception's sensitive message.
        assertEquals(failure, assertThrows(IllegalStateException.class,
                () -> reviewer.decide(id,
                        JdbcEntityListRemovalReviewRegister.Decision.APPROVED)));
        var candidate = new EntityListSnapshotLedgerCoordinator.Head(
                EntityListSnapshotEvidence.fromVerifiedBytes(
                        PROJECT, FORM, FILENAME, 2L,
                        Map.of("odkMediaType", "entityList"), SECOND, md5(SECOND), 1L)
                        .orElseThrow(), SECOND);
        assertEquals(failure, assertThrows(IllegalStateException.class,
                () -> executor.validateApprovedReview(id, candidate)));

        try (Connection c = fixture.source.getConnection();
             var stmt = c.prepareStatement(
                     "SELECT operation,reason,actor_id FROM entity_list_denial_evidence "
                             + "WHERE review_id=? ORDER BY operation")) {
            stmt.setObject(1, id);
            try (var rows = stmt.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("APPROVAL_VALIDATION", rows.getString("operation"));
                assertEquals("UNAVAILABLE", rows.getString("reason"));
                assertEquals(actor, rows.getObject("actor_id", UUID.class));
                assertTrue(rows.next());
                assertEquals("REVIEW_DECISION", rows.getString("operation"));
                assertEquals("UNAVAILABLE", rows.getString("reason"));
                assertEquals(actor, rows.getObject("actor_id", UUID.class));
                assertFalse(rows.next());
            }
        }
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void exceptionAuditFailureDoesNotSilentlyDiscardOriginalFailure() throws Exception {
        var fixture = new Fixture();
        UUID id = UUID.randomUUID();
        var identity = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var original = new IllegalStateException("synthetic web identity lookup error");
        org.mockito.Mockito.when(identity.getCurrentWebUser()).thenThrow(original);
        var register = org.mockito.Mockito.mock(JdbcEntityListRemovalReviewRegister.class);
        var writer = new JdbcEntityListDenialEvidenceWriter(fixture.source);
        var reviewer = new EntityListAuthenticatedReviewService(
                identity,
                org.mockito.Mockito.mock(EntityListSnapshotWebAuthorization.class),
                roleGate(JdbcEntityListRemovalRoleGate.Role.REVIEWER),
                register, writer);
        try (Connection c = fixture.source.getConnection();
             var stmt = c.createStatement()) {
            stmt.execute("DROP TABLE entity_list_denial_evidence");
        }
        var exception = assertThrows(IllegalStateException.class,
                () -> reviewer.decide(id,
                        JdbcEntityListRemovalReviewRegister.Decision.APPROVED));
        assertTrue(exception.getMessage().contains("Cannot persist denial evidence"));
        assertEquals(1, exception.getSuppressed().length);
        assertEquals(original, exception.getSuppressed()[0]);
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void independentRemovalDelegationsRequireExplicitActiveRoleAndManagerAuthority()
            throws Exception {
        var fixture = new Fixture();
        UUID actor = UUID.randomUUID();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var reviewer = new JdbcEntityListRemovalRoleGate(
                (connection, identity, scope) -> actor.equals(identity) && key.equals(scope),
                JdbcEntityListRemovalRoleGate.Role.REVIEWER);
        var executor = new JdbcEntityListRemovalRoleGate(
                (connection, identity, scope) -> actor.equals(identity) && key.equals(scope),
                JdbcEntityListRemovalRoleGate.Role.EXECUTOR);
        var deniedManager = new JdbcEntityListRemovalRoleGate(
                (connection, identity, scope) -> false,
                JdbcEntityListRemovalRoleGate.Role.REVIEWER);
        try (Connection connection = fixture.source.getConnection()) {
            connection.setAutoCommit(false);
            assertFalse(reviewer.mayInitialize(connection, actor, key));
            assertFalse(executor.mayInitialize(connection, actor, key));
            try (var insert = connection.prepareStatement(
                    "INSERT INTO entity_list_removal_role_grants "
                            + "(project_id,user_id,role_name,is_active) VALUES (?,?,?,TRUE)")) {
                insert.setObject(1, PROJECT);
                insert.setObject(2, actor);
                insert.setString(3, "REVIEWER");
                assertEquals(1, insert.executeUpdate());
            }
            assertTrue(reviewer.mayInitialize(connection, actor, key));
            assertFalse(executor.mayInitialize(connection, actor, key));
            assertFalse(deniedManager.mayInitialize(connection, actor, key));
            try (var insert = connection.prepareStatement(
                    "INSERT INTO entity_list_removal_role_grants "
                            + "(project_id,user_id,role_name,is_active) VALUES (?,?,?,TRUE)")) {
                insert.setObject(1, PROJECT);
                insert.setObject(2, actor);
                insert.setString(3, "EXECUTOR");
                assertEquals(1, insert.executeUpdate());
            }
            assertTrue(executor.mayInitialize(connection, actor, key));
            try (var revoke = connection.prepareStatement(
                    "UPDATE entity_list_removal_role_grants SET is_active=FALSE "
                            + "WHERE project_id=? AND user_id=? AND role_name=?")) {
                revoke.setObject(1, PROJECT);
                revoke.setObject(2, actor);
                revoke.setString(3, "REVIEWER");
                assertEquals(1, revoke.executeUpdate());
            }
            assertFalse(reviewer.mayInitialize(connection, actor, key));
            assertTrue(executor.mayInitialize(connection, actor, key));
            assertFalse(executor.mayInitialize(connection, UUID.randomUUID(), key));
            connection.rollback(); // disposable isolated authorization evidence only
        }
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void authenticatedFacadesRejectIncorrectDelegationRoleAtConstruction() throws Exception {
        var fixture = new Fixture();
        var identity = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var policy = org.mockito.Mockito.mock(EntityListSnapshotWebAuthorization.class);
        var writer = new JdbcEntityListDenialEvidenceWriter(fixture.source);
        var register = new JdbcEntityListRemovalReviewRegister(fixture.source);
        var seal = new JdbcEntityListRemovalApprovalSeal(fixture.source);
        var reviewerRole = roleGate(JdbcEntityListRemovalRoleGate.Role.REVIEWER);
        var executorRole = roleGate(JdbcEntityListRemovalRoleGate.Role.EXECUTOR);
        assertThrows(IllegalArgumentException.class, () ->
                new EntityListAuthenticatedReviewService(
                        identity, policy, executorRole, register, writer));
        assertThrows(IllegalArgumentException.class, () ->
                new EntityListAuthenticatedSealService(
                        identity, policy, reviewerRole, register, seal, writer));
    }

    @Test
    void reviewerGrantLockSerializesRevocationAndPreventsStaleAuthorization()
            throws Exception {
        var fixture = new Fixture();
        UUID actor = UUID.randomUUID();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var reviewer = new JdbcEntityListRemovalRoleGate(
                (connection, identity, scope) -> actor.equals(identity) && key.equals(scope),
                JdbcEntityListRemovalRoleGate.Role.REVIEWER);
        try (Connection setup = fixture.source.getConnection();
             var grant = setup.prepareStatement(
                     "INSERT INTO entity_list_removal_role_grants "
                             + "(project_id,user_id,role_name,is_active) "
                             + "VALUES (?,?,?,TRUE)")) {
            grant.setObject(1, PROJECT);
            grant.setObject(2, actor);
            grant.setString(3, "REVIEWER");
            assertEquals(1, grant.executeUpdate());
        }

        // Simulate an approval transaction that has verified and locked the
        // delegation row, but has not yet committed its review decision.
        try (Connection approval = fixture.source.getConnection();
             Connection revocation = fixture.source.getConnection()) {
            approval.setAutoCommit(false);
            revocation.setAutoCommit(false);
            assertTrue(reviewer.mayInitialize(approval, actor, key));

            // A concurrent writer must not revoke an already locked grant
            // behind the approval transaction. PostgreSQL times out instead
            // of letting a stale grant mutation overtake that transaction.
            try (var timeout = revocation.createStatement()) {
                timeout.execute("SET LOCAL lock_timeout = '250ms'");
            }
            try (var revoke = revocation.prepareStatement(
                    "UPDATE entity_list_removal_role_grants "
                            + "SET is_active=FALSE WHERE project_id=? "
                            + "AND user_id=? AND role_name='REVIEWER'")) {
                revoke.setObject(1, PROJECT);
                revoke.setObject(2, actor);
                SQLException blocked = assertThrows(SQLException.class, revoke::executeUpdate);
                assertEquals("55P03", blocked.getSQLState());
            }
            revocation.rollback();
            approval.commit();

            // Once the guarded transaction is completed, revocation may
            // commit. A fresh reviewer check MUST reject the revoked grant.
            try (var revoke = revocation.prepareStatement(
                    "UPDATE entity_list_removal_role_grants "
                            + "SET is_active=FALSE WHERE project_id=? "
                            + "AND user_id=? AND role_name='REVIEWER'")) {
                revoke.setObject(1, PROJECT);
                revoke.setObject(2, actor);
                assertEquals(1, revoke.executeUpdate());
            }
            revocation.commit();
        }
        try (Connection fresh = fixture.source.getConnection()) {
            fresh.setAutoCommit(false);
            assertFalse(reviewer.mayInitialize(fresh, actor, key));
            fresh.rollback();
        }
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void committedExecutorRevocationWinsAgainstLaterValidation()
            throws Exception {
        var fixture = new Fixture();
        UUID actor = UUID.randomUUID();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var executor = new JdbcEntityListRemovalRoleGate(
                (connection, identity, scope) -> actor.equals(identity) && key.equals(scope),
                JdbcEntityListRemovalRoleGate.Role.EXECUTOR);
        try (Connection revokeFirst = fixture.source.getConnection()) {
            revokeFirst.setAutoCommit(false);
            try (var grant = revokeFirst.prepareStatement(
                    "INSERT INTO entity_list_removal_role_grants "
                            + "(project_id,user_id,role_name,is_active) "
                            + "VALUES (?,?,?,FALSE)")) {
                grant.setObject(1, PROJECT);
                grant.setObject(2, actor);
                grant.setString(3, "EXECUTOR");
                assertEquals(1, grant.executeUpdate());
            }
            revokeFirst.commit();
        }
        try (Connection late = fixture.source.getConnection()) {
            late.setAutoCommit(false);
            assertFalse(executor.mayInitialize(late, actor, key));
            late.rollback();
        }
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void authorizedGrantLifecycleAtomicallyAuditsChangesAndBlocksSelfEscalation()
            throws Exception {
        var fixture = new Fixture();
        UUID admin = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var identity = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var user = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
        org.mockito.Mockito.when(user.getId()).thenReturn(admin);
        org.mockito.Mockito.when(user.getIsActive()).thenReturn(true);
        org.mockito.Mockito.when(identity.getCurrentWebUser())
                .thenReturn(java.util.Optional.of(user));
        var adminService = new EntityListRemovalGrantAdministration(fixture.source,
                identity, (connection, actor, scope) ->
                        admin.equals(actor) && key.equals(scope));
        try (Connection c = fixture.source.getConnection();
             var insert = c.prepareStatement(
                     "INSERT INTO entity_list_removal_grant_administrators "
                             + "(project_id,administrator_id,is_active) VALUES (?,?,TRUE)")) {
            insert.setObject(1, PROJECT);
            insert.setObject(2, admin);
            assertEquals(1, insert.executeUpdate());
        }
        // Project-manager authority alone must not be enough.
        var managerOnly = new EntityListRemovalGrantAdministration(fixture.source,
                identity, (connection, actor, scope) -> false);
        assertFalse(managerOnly.change(key, subject,
                JdbcEntityListRemovalRoleGate.Role.REVIEWER,
                EntityListRemovalGrantAdministration.Change.GRANT));
        assertFalse(adminService.change(key, admin,
                JdbcEntityListRemovalRoleGate.Role.EXECUTOR,
                EntityListRemovalGrantAdministration.Change.GRANT));
        assertFalse(adminService.change(key, subject,
                JdbcEntityListRemovalRoleGate.Role.EXECUTOR,
                EntityListRemovalGrantAdministration.Change.REVOKE));

        assertTrue(adminService.change(key, subject,
                JdbcEntityListRemovalRoleGate.Role.REVIEWER,
                EntityListRemovalGrantAdministration.Change.GRANT));
        assertFalse(adminService.change(key, subject,
                JdbcEntityListRemovalRoleGate.Role.REVIEWER,
                EntityListRemovalGrantAdministration.Change.GRANT));
        assertTrue(adminService.change(key, subject,
                JdbcEntityListRemovalRoleGate.Role.REVIEWER,
                EntityListRemovalGrantAdministration.Change.REVOKE));
        assertFalse(adminService.change(key, subject,
                JdbcEntityListRemovalRoleGate.Role.REVIEWER,
                EntityListRemovalGrantAdministration.Change.REVOKE));
        assertTrue(adminService.change(key, subject,
                JdbcEntityListRemovalRoleGate.Role.REVIEWER,
                EntityListRemovalGrantAdministration.Change.GRANT));

        try (Connection c = fixture.source.getConnection();
             var q = c.prepareStatement(
                     "SELECT action,administrator_id,subject_id "
                             + "FROM entity_list_removal_grant_audit "
                             + "WHERE project_id=? ORDER BY recorded_at,event_id")) {
            q.setObject(1, PROJECT);
            try (var rs = q.executeQuery()) {
                int count = 0;
                int grants = 0;
                int revokes = 0;
                while (rs.next()) {
                    count++;
                    if ("GRANT".equals(rs.getString("action"))) grants++;
                    if ("REVOKE".equals(rs.getString("action"))) revokes++;
                    assertEquals(admin, rs.getObject("administrator_id", UUID.class));
                    assertEquals(subject, rs.getObject("subject_id", UUID.class));
                }
                assertEquals(3, count);
                assertEquals(2, grants);
                assertEquals(1, revokes);
            }
        }
        try (Connection c = fixture.source.getConnection();
             var stmt = c.createStatement()) {
            assertThrows(SQLException.class,
                    () -> stmt.executeUpdate("DELETE FROM entity_list_removal_grant_audit"));
        }
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void grantChangeFailsClosedAndRollsBackWhenAuditStorageUnavailable()
            throws Exception {
        var fixture = new Fixture();
        UUID admin = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var identity = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var user = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
        org.mockito.Mockito.when(user.getId()).thenReturn(admin);
        org.mockito.Mockito.when(user.getIsActive()).thenReturn(true);
        org.mockito.Mockito.when(identity.getCurrentWebUser())
                .thenReturn(java.util.Optional.of(user));
        try (Connection c = fixture.source.getConnection();
             var insert = c.prepareStatement(
                     "INSERT INTO entity_list_removal_grant_administrators "
                             + "(project_id,administrator_id) VALUES (?,?)")) {
            insert.setObject(1, PROJECT);
            insert.setObject(2, admin);
            assertEquals(1, insert.executeUpdate());
        }
        var service = new EntityListRemovalGrantAdministration(fixture.source,
                identity, (connection, actor, scope) -> true);
        try (Connection c = fixture.source.getConnection();
             var ddl = c.createStatement()) {
            ddl.execute("DROP TABLE entity_list_removal_grant_audit");
        }
        assertThrows(IllegalStateException.class, () ->
                service.change(key, subject,
                        JdbcEntityListRemovalRoleGate.Role.EXECUTOR,
                        EntityListRemovalGrantAdministration.Change.GRANT));
        try (Connection c = fixture.source.getConnection();
             var query = c.prepareStatement(
                     "SELECT COUNT(*) FROM entity_list_removal_role_grants "
                             + "WHERE project_id=? AND user_id=?")) {
            query.setObject(1, PROJECT);
            query.setObject(2, subject);
            try (var rs = query.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0L, rs.getLong(1));
            }
        }
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void grantAdministratorRevocationCannotOvertakeLockedInFlightGrant()
            throws Exception {
        var fixture = new Fixture();
        UUID admin = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var identity = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var user = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
        org.mockito.Mockito.when(user.getId()).thenReturn(admin);
        org.mockito.Mockito.when(user.getIsActive()).thenReturn(true);
        org.mockito.Mockito.when(identity.getCurrentWebUser())
                .thenReturn(java.util.Optional.of(user));
        try (Connection connection = fixture.source.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO entity_list_removal_grant_administrators "
                             + "(project_id,administrator_id,is_active) VALUES (?,?,TRUE)")) {
            statement.setObject(1, PROJECT);
            statement.setObject(2, admin);
            assertEquals(1, statement.executeUpdate());
        }
        // Hold the administrator delegation row in the same manner as an
        // active issuance transaction. Its revocation must serialize.
        try (Connection issuing = fixture.source.getConnection();
             Connection revoking = fixture.source.getConnection()) {
            issuing.setAutoCommit(false);
            revoking.setAutoCommit(false);
            try (var lock = issuing.prepareStatement(
                    "SELECT administrator_id FROM entity_list_removal_grant_administrators "
                            + "WHERE project_id=? AND administrator_id=? "
                            + "AND is_active=TRUE FOR UPDATE")) {
                lock.setObject(1, PROJECT);
                lock.setObject(2, admin);
                try (var rows = lock.executeQuery()) {
                    assertTrue(rows.next());
                }
            }
            try (var timeout = revoking.createStatement()) {
                timeout.execute("SET LOCAL lock_timeout = '250ms'");
            }
            try (var revoke = revoking.prepareStatement(
                    "UPDATE entity_list_removal_grant_administrators "
                            + "SET is_active=FALSE WHERE project_id=? "
                            + "AND administrator_id=?")) {
                revoke.setObject(1, PROJECT);
                revoke.setObject(2, admin);
                var blocked = assertThrows(SQLException.class, revoke::executeUpdate);
                assertEquals("55P03", blocked.getSQLState());
            }
            revoking.rollback();
            issuing.commit();
            try (var revoke = revoking.prepareStatement(
                    "UPDATE entity_list_removal_grant_administrators "
                            + "SET is_active=FALSE WHERE project_id=? "
                            + "AND administrator_id=?")) {
                revoke.setObject(1, PROJECT);
                revoke.setObject(2, admin);
                assertEquals(1, revoke.executeUpdate());
            }
            revoking.commit();
        }
        var service = new EntityListRemovalGrantAdministration(fixture.source,
                identity, (connection, actor, scope) ->
                        admin.equals(actor) && key.equals(scope));
        assertFalse(service.change(key, subject,
                JdbcEntityListRemovalRoleGate.Role.EXECUTOR,
                EntityListRemovalGrantAdministration.Change.GRANT));
        try (Connection connection = fixture.source.getConnection();
             var q = connection.createStatement();
             var events = q.executeQuery(
                     "SELECT COUNT(*) FROM entity_list_removal_grant_audit")) {
            assertTrue(events.next());
            assertEquals(0L, events.getLong(1));
        }
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void concurrentGrantAdministratorsCannotIssueDuplicateActiveRoleOrAudit()
            throws Exception {
        var fixture = new Fixture();
        UUID administratorA = UUID.randomUUID();
        UUID administratorB = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        try (Connection c = fixture.source.getConnection();
             var insert = c.prepareStatement(
                     "INSERT INTO entity_list_removal_grant_administrators "
                             + "(project_id,administrator_id,is_active) VALUES (?,?,TRUE)")) {
            for (UUID actor : new UUID[]{administratorA, administratorB}) {
                insert.setObject(1, PROJECT);
                insert.setObject(2, actor);
                assertEquals(1, insert.executeUpdate());
            }
        }
        java.util.function.Function<UUID, EntityListRemovalGrantAdministration> serviceFor =
                actor -> {
                    var identity = org.mockito.Mockito.mock(
                            org.odk.platform.security.CurrentUserService.class);
                    var user = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
                    org.mockito.Mockito.when(user.getId()).thenReturn(actor);
                    org.mockito.Mockito.when(user.getIsActive()).thenReturn(true);
                    org.mockito.Mockito.when(identity.getCurrentWebUser())
                            .thenReturn(java.util.Optional.of(user));
                    return new EntityListRemovalGrantAdministration(fixture.source,
                            identity, (connection, current, scope) ->
                                    actor.equals(current) && key.equals(scope));
                };
        var first = serviceFor.apply(administratorA);
        var second = serviceFor.apply(administratorB);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var a = pool.submit(() -> {
                start.await();
                return first.change(key, subject, JdbcEntityListRemovalRoleGate.Role.REVIEWER,
                        EntityListRemovalGrantAdministration.Change.GRANT);
            });
            var b = pool.submit(() -> {
                start.await();
                return second.change(key, subject, JdbcEntityListRemovalRoleGate.Role.REVIEWER,
                        EntityListRemovalGrantAdministration.Change.GRANT);
            });
            start.countDown();
            boolean resultA = a.get(15, java.util.concurrent.TimeUnit.SECONDS);
            boolean resultB = b.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(resultA ^ resultB);
        } finally {
            pool.shutdownNow();
        }
        try (Connection c = fixture.source.getConnection();
             var q = c.createStatement()) {
            try (var grants = q.executeQuery(
                    "SELECT COUNT(*) FROM entity_list_removal_role_grants "
                            + "WHERE role_name='REVIEWER' AND is_active=TRUE")) {
                assertTrue(grants.next());
                assertEquals(1L, grants.getLong(1));
            }
            try (var events = q.executeQuery(
                    "SELECT COUNT(*) FROM entity_list_removal_grant_audit "
                            + "WHERE role_name='REVIEWER' AND action='GRANT'")) {
                assertTrue(events.next());
                assertEquals(1L, events.getLong(1));
            }
        }
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void independentCustodianAtomicallyAppointsRevokesAndReappointsGrantAdministrator()
            throws Exception {
        var fixture = new Fixture();
        UUID custodian = UUID.randomUUID();
        UUID administrator = UUID.randomUUID();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var current = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var webUser = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
        org.mockito.Mockito.when(webUser.getId()).thenReturn(custodian);
        org.mockito.Mockito.when(webUser.getIsActive()).thenReturn(true);
        org.mockito.Mockito.when(current.getCurrentWebUser())
                .thenReturn(java.util.Optional.of(webUser));
        var custody = new EntityListGrantAdministratorCustody(
                fixture.source, current, (connection, actor, scope) ->
                        custodian.equals(actor) && key.equals(scope));
        var deniedManager = new EntityListGrantAdministratorCustody(
                fixture.source, current, (connection, actor, scope) -> false);
        assertFalse(custody.change(key, administrator,
                EntityListGrantAdministratorCustody.Change.APPOINT));
        try (Connection c = fixture.source.getConnection();
             var insert = c.prepareStatement(
                     "INSERT INTO entity_list_grant_custodians "
                             + "(project_id,custodian_id,is_active) VALUES (?,?,TRUE)")) {
            insert.setObject(1, PROJECT);
            insert.setObject(2, custodian);
            assertEquals(1, insert.executeUpdate());
        }
        assertFalse(deniedManager.change(key, administrator,
                EntityListGrantAdministratorCustody.Change.APPOINT));
        assertFalse(custody.change(key, custodian,
                EntityListGrantAdministratorCustody.Change.APPOINT));
        assertFalse(custody.change(key, administrator,
                EntityListGrantAdministratorCustody.Change.REVOKE));
        assertTrue(custody.change(key, administrator,
                EntityListGrantAdministratorCustody.Change.APPOINT));
        assertFalse(custody.change(key, administrator,
                EntityListGrantAdministratorCustody.Change.APPOINT));
        assertTrue(custody.change(key, administrator,
                EntityListGrantAdministratorCustody.Change.REVOKE));
        assertFalse(custody.change(key, administrator,
                EntityListGrantAdministratorCustody.Change.REVOKE));
        assertTrue(custody.change(key, administrator,
                EntityListGrantAdministratorCustody.Change.APPOINT));
        try (Connection c = fixture.source.getConnection();
             var q = c.createStatement()) {
            try (var events = q.executeQuery(
                    "SELECT action,custodian_id,administrator_id "
                            + "FROM entity_list_grant_administrator_audit")) {
                int count = 0;
                int appointments = 0;
                int revocations = 0;
                while (events.next()) {
                    count++;
                    if ("APPOINT".equals(events.getString("action"))) appointments++;
                    if ("REVOKE".equals(events.getString("action"))) revocations++;
                    assertEquals(custodian, events.getObject("custodian_id", UUID.class));
                    assertEquals(administrator, events.getObject("administrator_id", UUID.class));
                }
                assertEquals(3, count);
                assertEquals(2, appointments);
                assertEquals(1, revocations);
            }
            try (var active = q.executeQuery(
                    "SELECT is_active FROM entity_list_removal_grant_administrators")) {
                assertTrue(active.next());
                assertTrue(active.getBoolean(1));
                assertFalse(active.next());
            }
            assertThrows(SQLException.class, () -> q.executeUpdate(
                    "DELETE FROM entity_list_grant_administrator_audit"));
        }
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void custodianRevocationAndAuditFailureBothFailClosed() throws Exception {
        var fixture = new Fixture();
        UUID custodian = UUID.randomUUID();
        UUID administrator = UUID.randomUUID();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var current = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var webUser = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
        org.mockito.Mockito.when(webUser.getId()).thenReturn(custodian);
        org.mockito.Mockito.when(webUser.getIsActive()).thenReturn(true);
        org.mockito.Mockito.when(current.getCurrentWebUser())
                .thenReturn(java.util.Optional.of(webUser));
        var custody = new EntityListGrantAdministratorCustody(
                fixture.source, current, (connection, actor, scope) -> true);
        try (Connection c = fixture.source.getConnection();
             var insert = c.prepareStatement(
                     "INSERT INTO entity_list_grant_custodians "
                             + "(project_id,custodian_id,is_active) VALUES (?,?,TRUE)")) {
            insert.setObject(1, PROJECT);
            insert.setObject(2, custodian);
            assertEquals(1, insert.executeUpdate());
        }
        try (Connection c = fixture.source.getConnection();
             var q = c.createStatement()) {
            q.execute("DROP TABLE entity_list_grant_administrator_audit");
        }
        assertThrows(IllegalStateException.class, () ->
                custody.change(key, administrator,
                        EntityListGrantAdministratorCustody.Change.APPOINT));
        try (Connection c = fixture.source.getConnection();
             var check = c.createStatement();
             var rows = check.executeQuery(
                     "SELECT COUNT(*) FROM entity_list_removal_grant_administrators")) {
            assertTrue(rows.next());
            assertEquals(0L, rows.getLong(1));
        }
        try (Connection c = fixture.source.getConnection();
             var disable = c.prepareStatement(
                     "UPDATE entity_list_grant_custodians SET is_active=FALSE "
                             + "WHERE project_id=? AND custodian_id=?")) {
            disable.setObject(1, PROJECT);
            disable.setObject(2, custodian);
            assertEquals(1, disable.executeUpdate());
        }
        assertFalse(custody.change(key, administrator,
                EntityListGrantAdministratorCustody.Change.APPOINT));
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void independentThreePersonCustodianAttestationNeverMutatesCustodianAuthority()
            throws Exception {
        var fixture = new Fixture();
        UUID proposer = UUID.randomUUID();
        UUID witnessA = UUID.randomUUID();
        UUID witnessB = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var current = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var activeUser = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
        var actor = new java.util.concurrent.atomic.AtomicReference<>(proposer);
        org.mockito.Mockito.when(activeUser.getId()).thenAnswer(call -> actor.get());
        org.mockito.Mockito.when(activeUser.getIsActive()).thenReturn(true);
        org.mockito.Mockito.when(current.getCurrentWebUser())
                .thenReturn(java.util.Optional.of(activeUser));
        try (Connection c = fixture.source.getConnection();
             var insert = c.prepareStatement(
                     "INSERT INTO entity_list_grant_custodians "
                             + "(project_id,custodian_id,is_active) VALUES (?,?,TRUE)")) {
            for (UUID approved : new UUID[]{proposer,witnessA,witnessB}) {
                insert.setObject(1, PROJECT);
                insert.setObject(2, approved);
                assertEquals(1, insert.executeUpdate());
            }
        }
        var quorum = new EntityListCustodianChangeQuorum(
                fixture.source, current, (connection, candidate, scope) ->
                        key.equals(scope)
                        && !candidate.equals(subject));
        assertTrue(quorum.propose(proposalId, key, subject,
                EntityListCustodianChangeQuorum.Action.APPOINT));
        assertFalse(quorum.propose(proposalId, key, subject,
                EntityListCustodianChangeQuorum.Action.APPOINT));
        // A proposer may not witness their own request.
        assertFalse(quorum.witness(proposalId));
        actor.set(subject);
        assertFalse(quorum.witness(proposalId));
        actor.set(witnessA);
        assertTrue(quorum.witness(proposalId));
        assertFalse(quorum.witness(proposalId));
        try (Connection c = fixture.source.getConnection();
             var q = c.prepareStatement(
                     "SELECT state FROM entity_list_custodian_change_proposals WHERE id=?")) {
            q.setObject(1, proposalId);
            try (var rows = q.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("PENDING", rows.getString(1));
            }
        }
        actor.set(witnessB);
        assertTrue(quorum.witness(proposalId));
        assertFalse(quorum.witness(proposalId));
        try (Connection c = fixture.source.getConnection();
             var q = c.prepareStatement(
                     "SELECT state FROM entity_list_custodian_change_proposals WHERE id=?")) {
            q.setObject(1, proposalId);
            try (var rows = q.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("ENDORSED", rows.getString(1));
            }
        }
        try (Connection c = fixture.source.getConnection();
             var q = c.createStatement()) {
            try (var count = q.executeQuery(
                    "SELECT COUNT(*) FROM entity_list_custodian_change_witnesses")) {
                assertTrue(count.next());
                assertEquals(2L, count.getLong(1));
            }
            // Quorum is EVIDENCE ONLY: the target has not been appointed.
            try (var count = q.executeQuery(
                    "SELECT COUNT(*) FROM entity_list_grant_custodians "
                            + "WHERE custodian_id='" + subject + "'")) {
                assertTrue(count.next());
                assertEquals(0L, count.getLong(1));
            }
            assertThrows(SQLException.class, () -> q.executeUpdate(
                    "DELETE FROM entity_list_custodian_change_witnesses"));
        }
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void revokingWitnessBeforeAttestationPreventsQuorumAndKeepsProposalPending()
            throws Exception {
        var fixture = new Fixture();
        UUID proposer = UUID.randomUUID();
        UUID revokedWitness = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var current = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var webUser = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
        var actor = new java.util.concurrent.atomic.AtomicReference<>(proposer);
        org.mockito.Mockito.when(webUser.getId()).thenAnswer(call -> actor.get());
        org.mockito.Mockito.when(webUser.getIsActive()).thenReturn(true);
        org.mockito.Mockito.when(current.getCurrentWebUser())
                .thenReturn(java.util.Optional.of(webUser));
        try (Connection c = fixture.source.getConnection();
             var grant = c.prepareStatement(
                     "INSERT INTO entity_list_grant_custodians "
                             + "(project_id,custodian_id,is_active) VALUES (?,?,TRUE)")) {
            for (UUID trusted : new UUID[]{proposer,revokedWitness}) {
                grant.setObject(1, PROJECT);
                grant.setObject(2, trusted);
                assertEquals(1, grant.executeUpdate());
            }
        }
        var quorum = new EntityListCustodianChangeQuorum(
                fixture.source, current, (connection, candidate, scope) -> key.equals(scope));
        assertTrue(quorum.propose(proposalId, key, subject,
                EntityListCustodianChangeQuorum.Action.REVOKE));
        try (Connection c = fixture.source.getConnection();
             var revoke = c.prepareStatement(
                     "UPDATE entity_list_grant_custodians SET is_active=FALSE "
                             + "WHERE project_id=? AND custodian_id=?")) {
            revoke.setObject(1, PROJECT);
            revoke.setObject(2, revokedWitness);
            assertEquals(1, revoke.executeUpdate());
        }
        actor.set(revokedWitness);
        assertFalse(quorum.witness(proposalId));
        try (Connection c = fixture.source.getConnection();
             var q = c.createStatement();
             var rs = q.executeQuery(
                     "SELECT state FROM entity_list_custodian_change_proposals "
                             + "WHERE id='" + proposalId + "'")) {
            assertTrue(rs.next());
            assertEquals("PENDING", rs.getString(1));
        }
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void custodianReadinessIsOneUseRequiresFourDistinctActiveCustodiansAndNeverExecutes()
            throws Exception {
        var fixture = new Fixture();
        UUID initiator = UUID.randomUUID();
        UUID witnessA = UUID.randomUUID();
        UUID witnessB = UUID.randomUUID();
        UUID validator = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        UUID proposal = UUID.randomUUID();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var current = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var user = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
        var actor = new java.util.concurrent.atomic.AtomicReference<>(validator);
        org.mockito.Mockito.when(user.getId()).thenAnswer(invocation -> actor.get());
        org.mockito.Mockito.when(user.getIsActive()).thenReturn(true);
        org.mockito.Mockito.when(current.getCurrentWebUser())
                .thenReturn(java.util.Optional.of(user));
        try (Connection c = fixture.source.getConnection()) {
            try (var grant = c.prepareStatement(
                    "INSERT INTO entity_list_grant_custodians "
                            + "(project_id,custodian_id,is_active) VALUES (?,?,TRUE)")) {
                for (UUID id : new UUID[]{initiator,witnessA,witnessB,validator}) {
                    grant.setObject(1, PROJECT);
                    grant.setObject(2, id);
                    assertEquals(1, grant.executeUpdate());
                }
            }
            try (var pending = c.prepareStatement(
                    "INSERT INTO entity_list_custodian_change_proposals "
                            + "(id,project_id,form_id,filename,subject_id,initiated_by,action,state) "
                            + "VALUES (?,?,?,?,?,?,'APPOINT','ENDORSED')")) {
                pending.setObject(1, proposal);
                pending.setObject(2, PROJECT);
                pending.setObject(3, FORM);
                pending.setString(4, FILENAME);
                pending.setObject(5, subject);
                pending.setObject(6, initiator);
                assertEquals(1, pending.executeUpdate());
            }
            try (var witnesses = c.prepareStatement(
                    "INSERT INTO entity_list_custodian_change_witnesses "
                            + "(proposal_id,witness_id) VALUES (?,?)")) {
                for (UUID witness : new UUID[]{witnessA,witnessB}) {
                    witnesses.setObject(1, proposal);
                    witnesses.setObject(2, witness);
                    assertEquals(1, witnesses.executeUpdate());
                }
            }
        }
        var seal = new EntityListCustodianReadinessSeal(fixture.source, current,
                (connection, identity, scope) -> key.equals(scope));
        actor.set(initiator);
        assertFalse(seal.seal(proposal)); // proposer cannot validate
        actor.set(witnessA);
        assertFalse(seal.seal(proposal)); // witness cannot validate
        actor.set(validator);
        assertTrue(seal.seal(proposal));
        assertFalse(seal.seal(proposal)); // no second receipt / no replay
        try (Connection c = fixture.source.getConnection();
             var statement = c.createStatement()) {
            try (var readiness = statement.executeQuery(
                    "SELECT proposal_id,validated_by FROM entity_list_custodian_change_readiness")) {
                assertTrue(readiness.next());
                assertEquals(proposal, readiness.getObject("proposal_id", UUID.class));
                assertEquals(validator, readiness.getObject("validated_by", UUID.class));
                assertFalse(readiness.next());
            }
            try (var custody = statement.executeQuery(
                    "SELECT COUNT(*) FROM entity_list_grant_custodians "
                            + "WHERE custodian_id='" + subject + "'")) {
                assertTrue(custody.next());
                assertEquals(0L, custody.getLong(1)); // receipt cannot appoint subject
            }
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "DELETE FROM entity_list_custodian_change_readiness"));
        }
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void custodianReadinessRejectsExpiredOrRevokedWitnessEvidence()
            throws Exception {
        var fixture = new Fixture();
        UUID initiator = UUID.randomUUID();
        UUID witnessA = UUID.randomUUID();
        UUID witnessB = UUID.randomUUID();
        UUID validator = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        UUID revoked = UUID.randomUUID();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var current = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var user = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
        org.mockito.Mockito.when(user.getId()).thenReturn(validator);
        org.mockito.Mockito.when(user.getIsActive()).thenReturn(true);
        org.mockito.Mockito.when(current.getCurrentWebUser())
                .thenReturn(java.util.Optional.of(user));
        try (Connection c = fixture.source.getConnection()) {
            try (var grant = c.prepareStatement(
                    "INSERT INTO entity_list_grant_custodians "
                            + "(project_id,custodian_id,is_active) VALUES (?,?,TRUE)")) {
                for (UUID id : new UUID[]{initiator,witnessA,witnessB,validator}) {
                    grant.setObject(1, PROJECT);
                    grant.setObject(2, id);
                    assertEquals(1, grant.executeUpdate());
                }
            }
            try (var pending = c.prepareStatement(
                    "INSERT INTO entity_list_custodian_change_proposals "
                            + "(id,project_id,form_id,filename,subject_id,initiated_by,"
                            + "action,state,created_at) VALUES "
                            + "(?,?,?,?,?,?,'REVOKE','ENDORSED',?)")) {
                for (UUID id : new UUID[]{expired,revoked}) {
                    pending.setObject(1, id);
                    pending.setObject(2, PROJECT);
                    pending.setObject(3, FORM);
                    pending.setString(4, FILENAME);
                    pending.setObject(5, subject);
                    pending.setObject(6, initiator);
                    pending.setObject(7, java.sql.Timestamp.from(
                            java.time.Instant.now().minusSeconds(
                                    id.equals(expired) ? 90000 : 30)));
                    assertEquals(1, pending.executeUpdate());
                }
            }
            try (var witnesses = c.prepareStatement(
                    "INSERT INTO entity_list_custodian_change_witnesses "
                            + "(proposal_id,witness_id) VALUES (?,?)")) {
                for (UUID id : new UUID[]{expired,revoked}) {
                    for (UUID witness : new UUID[]{witnessA,witnessB}) {
                        witnesses.setObject(1, id);
                        witnesses.setObject(2, witness);
                        assertEquals(1, witnesses.executeUpdate());
                    }
                }
            }
            try (var revoke = c.prepareStatement(
                    "UPDATE entity_list_grant_custodians SET is_active=FALSE "
                            + "WHERE project_id=? AND custodian_id=?")) {
                revoke.setObject(1, PROJECT);
                revoke.setObject(2, witnessA);
                assertEquals(1, revoke.executeUpdate());
            }
        }
        var seal = new EntityListCustodianReadinessSeal(fixture.source, current,
                (connection, identity, scope) -> key.equals(scope));
        assertFalse(seal.seal(expired));
        assertFalse(seal.seal(revoked));
        try (Connection c = fixture.source.getConnection();
             var statement = c.createStatement();
             var rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM entity_list_custodian_change_readiness")) {
            assertTrue(rows.next());
            assertEquals(0L, rows.getLong(1));
        }
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void separatelyAuthorizedCustodyExecutorConsumesOneUseReadinessAtomically()
            throws Exception {
        var fixture = new Fixture();
        UUID proposer = UUID.randomUUID();
        UUID witnessA = UUID.randomUUID();
        UUID witnessB = UUID.randomUUID();
        UUID validator = UUID.randomUUID();
        UUID officer = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        UUID proposal = UUID.randomUUID();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var current = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var user = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
        org.mockito.Mockito.when(user.getId()).thenReturn(officer);
        org.mockito.Mockito.when(user.getIsActive()).thenReturn(true);
        org.mockito.Mockito.when(current.getCurrentWebUser())
                .thenReturn(java.util.Optional.of(user));
        try (Connection c = fixture.source.getConnection()) {
            try (var insert = c.prepareStatement(
                    "INSERT INTO entity_list_grant_custodians "
                            + "(project_id,custodian_id,is_active) VALUES (?,?,TRUE)")) {
                for (UUID id : new UUID[]{proposer,witnessA,witnessB,validator}) {
                    insert.setObject(1, PROJECT);
                    insert.setObject(2, id);
                    assertEquals(1, insert.executeUpdate());
                }
            }
            try (var insert = c.prepareStatement(
                    "INSERT INTO entity_list_custodian_change_proposals "
                            + "(id,project_id,form_id,filename,subject_id,initiated_by,action,state) "
                            + "VALUES (?,?,?,?,?,?,'APPOINT','ENDORSED')")) {
                insert.setObject(1, proposal);
                insert.setObject(2, PROJECT);
                insert.setObject(3, FORM);
                insert.setString(4, FILENAME);
                insert.setObject(5, subject);
                insert.setObject(6, proposer);
                assertEquals(1, insert.executeUpdate());
            }
            try (var insert = c.prepareStatement(
                    "INSERT INTO entity_list_custodian_change_witnesses "
                            + "(proposal_id,witness_id) VALUES (?,?)")) {
                for (UUID witness : new UUID[]{witnessA,witnessB}) {
                    insert.setObject(1, proposal);
                    insert.setObject(2, witness);
                    assertEquals(1, insert.executeUpdate());
                }
            }
            try (var receipt = c.prepareStatement(
                    "INSERT INTO entity_list_custodian_change_readiness "
                            + "(proposal_id,validated_by) VALUES (?,?)")) {
                receipt.setObject(1, proposal);
                receipt.setObject(2, validator);
                assertEquals(1, receipt.executeUpdate());
            }
        }
        var executor = new EntityListCustodianChangeExecutor(fixture.source,
                current, (connection, actor, scope) -> key.equals(scope));
        assertFalse(executor.execute(proposal)); // no officer delegation
        try (Connection c = fixture.source.getConnection();
             var grant = c.prepareStatement(
                     "INSERT INTO entity_list_custodian_execution_officers "
                             + "(project_id,officer_id,is_active) VALUES (?,?,TRUE)")) {
            grant.setObject(1, PROJECT);
            grant.setObject(2, officer);
            assertEquals(1, grant.executeUpdate());
        }
        assertTrue(executor.execute(proposal));
        assertFalse(executor.execute(proposal)); // unique consumed proposal
        try (Connection c = fixture.source.getConnection();
             var statement = c.createStatement()) {
            try (var row = statement.executeQuery(
                    "SELECT COUNT(*) FROM entity_list_grant_custodians "
                            + "WHERE project_id='" + PROJECT + "' "
                            + "AND custodian_id='" + subject + "' AND is_active=TRUE")) {
                assertTrue(row.next());
                assertEquals(1L, row.getLong(1));
            }
            try (var row = statement.executeQuery(
                    "SELECT COUNT(*) FROM entity_list_custodian_change_executions")) {
                assertTrue(row.next());
                assertEquals(1L, row.getLong(1));
            }
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "DELETE FROM entity_list_custodian_change_executions"));
        }
        assertEquals(0, fixture.historyCount());
    }

    @Test
    void custodyExecutorRollsBackAppointmentIfExecutionAuditCannotPersist()
            throws Exception {
        var fixture = new Fixture();
        UUID proposer = UUID.randomUUID();
        UUID witnessA = UUID.randomUUID();
        UUID witnessB = UUID.randomUUID();
        UUID validator = UUID.randomUUID();
        UUID officer = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        UUID proposal = UUID.randomUUID();
        var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
        var current = org.mockito.Mockito.mock(
                org.odk.platform.security.CurrentUserService.class);
        var user = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
        org.mockito.Mockito.when(user.getId()).thenReturn(officer);
        org.mockito.Mockito.when(user.getIsActive()).thenReturn(true);
        org.mockito.Mockito.when(current.getCurrentWebUser())
                .thenReturn(java.util.Optional.of(user));
        try (Connection c = fixture.source.getConnection()) {
            try (var grant = c.prepareStatement(
                    "INSERT INTO entity_list_grant_custodians "
                            + "(project_id,custodian_id,is_active) VALUES (?,?,TRUE)")) {
                for (UUID id : new UUID[]{proposer,witnessA,witnessB,validator}) {
                    grant.setObject(1, PROJECT);
                    grant.setObject(2, id);
                    assertEquals(1, grant.executeUpdate());
                }
            }
            try (var grant = c.prepareStatement(
                    "INSERT INTO entity_list_custodian_execution_officers "
                            + "(project_id,officer_id) VALUES (?,?)")) {
                grant.setObject(1, PROJECT);
                grant.setObject(2, officer);
                assertEquals(1, grant.executeUpdate());
            }
            try (var proposalRow = c.prepareStatement(
                    "INSERT INTO entity_list_custodian_change_proposals "
                            + "(id,project_id,form_id,filename,subject_id,initiated_by,action,state) "
                            + "VALUES (?,?,?,?,?,?,'APPOINT','ENDORSED')")) {
                proposalRow.setObject(1, proposal);
                proposalRow.setObject(2, PROJECT);
                proposalRow.setObject(3, FORM);
                proposalRow.setString(4, FILENAME);
                proposalRow.setObject(5, subject);
                proposalRow.setObject(6, proposer);
                assertEquals(1, proposalRow.executeUpdate());
            }
            try (var witness = c.prepareStatement(
                    "INSERT INTO entity_list_custodian_change_witnesses "
                            + "(proposal_id,witness_id) VALUES (?,?)")) {
                for (UUID id : new UUID[]{witnessA,witnessB}) {
                    witness.setObject(1, proposal);
                    witness.setObject(2, id);
                    assertEquals(1, witness.executeUpdate());
                }
            }
            try (var readiness = c.prepareStatement(
                    "INSERT INTO entity_list_custodian_change_readiness "
                            + "(proposal_id,validated_by) VALUES (?,?)")) {
                readiness.setObject(1, proposal);
                readiness.setObject(2, validator);
                assertEquals(1, readiness.executeUpdate());
            }
            try (var ddl = c.createStatement()) {
                ddl.execute("DROP TABLE entity_list_custodian_change_executions");
            }
        }
        var executor = new EntityListCustodianChangeExecutor(fixture.source,
                current, (connection, actor, scope) -> key.equals(scope));
        assertThrows(IllegalStateException.class, () -> executor.execute(proposal));
        try (Connection c = fixture.source.getConnection();
             var check = c.prepareStatement(
                     "SELECT COUNT(*) FROM entity_list_grant_custodians "
                             + "WHERE project_id=? AND custodian_id=?")) {
            check.setObject(1, PROJECT);
            check.setObject(2, subject);
            try (var row = check.executeQuery()) {
                assertTrue(row.next());
                assertEquals(0L, row.getLong(1));
            }
        }
        assertEquals(0, fixture.historyCount());
    }

    private static final class CustodyExecutionScenario {
        final Fixture fixture;
        final UUID proposer = UUID.randomUUID();
        final UUID witnessA = UUID.randomUUID();
        final UUID witnessB = UUID.randomUUID();
        final UUID validator = UUID.randomUUID();
        final UUID officer = UUID.randomUUID();
        final UUID subject = UUID.randomUUID();
        final UUID proposal = UUID.randomUUID();
        final EntityListCustodianChangeExecutor executor;

        CustodyExecutionScenario(String action, long ageSeconds, boolean fifthCustodian)
                throws Exception {
            fixture = new Fixture();
            var key = new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, FILENAME);
            var identity = org.mockito.Mockito.mock(
                    org.odk.platform.security.CurrentUserService.class);
            var user = org.mockito.Mockito.mock(org.odk.platform.domain.User.class);
            org.mockito.Mockito.when(user.getId()).thenReturn(officer);
            org.mockito.Mockito.when(user.getIsActive()).thenReturn(true);
            org.mockito.Mockito.when(identity.getCurrentWebUser())
                    .thenReturn(java.util.Optional.of(user));
            executor = new EntityListCustodianChangeExecutor(fixture.source, identity,
                    (connection, actor, scope) -> officer.equals(actor) && key.equals(scope));
            try (Connection c = fixture.source.getConnection()) {
                try (var grant = c.prepareStatement(
                        "INSERT INTO entity_list_grant_custodians "
                                + "(project_id,custodian_id,is_active) VALUES (?,?,TRUE)")) {
                    for (UUID custodian : new UUID[]{proposer,witnessA,witnessB,validator}) {
                        grant.setObject(1, PROJECT);
                        grant.setObject(2, custodian);
                        assertEquals(1, grant.executeUpdate());
                    }
                    if (fifthCustodian) {
                        grant.setObject(1, PROJECT);
                        grant.setObject(2, subject);
                        assertEquals(1, grant.executeUpdate());
                    }
                }
                try (var grant = c.prepareStatement(
                        "INSERT INTO entity_list_custodian_execution_officers "
                                + "(project_id,officer_id,is_active) VALUES (?,?,TRUE)")) {
                    grant.setObject(1, PROJECT);
                    grant.setObject(2, officer);
                    assertEquals(1, grant.executeUpdate());
                }
                try (var p = c.prepareStatement(
                        "INSERT INTO entity_list_custodian_change_proposals "
                                + "(id,project_id,form_id,filename,subject_id,initiated_by,"
                                + "action,state,created_at) "
                                + "VALUES (?,?,?,?,?,?,?,'ENDORSED',?)")) {
                    p.setObject(1, proposal);
                    p.setObject(2, PROJECT);
                    p.setObject(3, FORM);
                    p.setString(4, FILENAME);
                    p.setObject(5, subject);
                    p.setObject(6, proposer);
                    p.setString(7, action);
                    p.setObject(8, java.sql.Timestamp.from(
                            java.time.Instant.now().minusSeconds(ageSeconds)));
                    assertEquals(1, p.executeUpdate());
                }
                try (var w = c.prepareStatement(
                        "INSERT INTO entity_list_custodian_change_witnesses "
                                + "(proposal_id,witness_id) VALUES (?,?)")) {
                    for (UUID witness : new UUID[]{witnessA,witnessB}) {
                        w.setObject(1, proposal);
                        w.setObject(2, witness);
                        assertEquals(1, w.executeUpdate());
                    }
                }
                try (var seal = c.prepareStatement(
                        "INSERT INTO entity_list_custodian_change_readiness "
                                + "(proposal_id,validated_by) VALUES (?,?)")) {
                    seal.setObject(1, proposal);
                    seal.setObject(2, validator);
                    assertEquals(1, seal.executeUpdate());
                }
            }
        }

        long executionCount() throws Exception {
            try (Connection c = fixture.source.getConnection();
                 var s = c.createStatement();
                 var r = s.executeQuery(
                         "SELECT COUNT(*) FROM entity_list_custodian_change_executions")) {
                r.next();
                return r.getLong(1);
            }
        }

        long activeCustodianCount() throws Exception {
            try (Connection c = fixture.source.getConnection();
                 var s = c.createStatement();
                 var r = s.executeQuery(
                         "SELECT COUNT(*) FROM entity_list_grant_custodians "
                                 + "WHERE is_active=TRUE")) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    @Test
    void custodyExecutionRejectsExpiredAndRevokedOfficerOrWitnessWithoutWrites()
            throws Exception {
        var expired = new CustodyExecutionScenario("APPOINT", 90000, false);
        assertFalse(expired.executor.execute(expired.proposal));
        assertEquals(0L, expired.executionCount());
        assertEquals(4L, expired.activeCustodianCount());

        var officerRevoked = new CustodyExecutionScenario("APPOINT", 10, false);
        try (Connection c = officerRevoked.fixture.source.getConnection();
             var revoke = c.prepareStatement(
                     "UPDATE entity_list_custodian_execution_officers "
                             + "SET is_active=FALSE WHERE officer_id=?")) {
            revoke.setObject(1, officerRevoked.officer);
            assertEquals(1, revoke.executeUpdate());
        }
        assertFalse(officerRevoked.executor.execute(officerRevoked.proposal));
        assertEquals(0L, officerRevoked.executionCount());
        assertEquals(4L, officerRevoked.activeCustodianCount());

        var witnessRevoked = new CustodyExecutionScenario("APPOINT", 10, false);
        try (Connection c = witnessRevoked.fixture.source.getConnection();
             var revoke = c.prepareStatement(
                     "UPDATE entity_list_grant_custodians "
                             + "SET is_active=FALSE WHERE custodian_id=?")) {
            revoke.setObject(1, witnessRevoked.witnessA);
            assertEquals(1, revoke.executeUpdate());
        }
        assertFalse(witnessRevoked.executor.execute(witnessRevoked.proposal));
        assertEquals(0L, witnessRevoked.executionCount());
        assertEquals(3L, witnessRevoked.activeCustodianCount());
        assertEquals(0, witnessRevoked.fixture.historyCount());
    }

    @Test
    void custodyExecutionPreservesFourCustodianFloorUnderRevocation()
            throws Exception {
        var onlyFour = new CustodyExecutionScenario("REVOKE", 10, false);
        assertFalse(onlyFour.executor.execute(onlyFour.proposal));
        assertEquals(4L, onlyFour.activeCustodianCount());
        assertEquals(0L, onlyFour.executionCount());

        var five = new CustodyExecutionScenario("REVOKE", 10, true);
        assertTrue(five.executor.execute(five.proposal));
        assertEquals(4L, five.activeCustodianCount());
        assertEquals(1L, five.executionCount());
        assertFalse(five.executor.execute(five.proposal));
        assertEquals(1L, five.executionCount());
        assertEquals(0, five.fixture.historyCount());
    }

    @Test
    void racingCustodyExecutorsCommitExactlyOneExecutionReceipt()
            throws Exception {
        var scenario = new CustodyExecutionScenario("APPOINT", 10, false);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                start.await();
                return scenario.executor.execute(scenario.proposal);
            });
            var second = pool.submit(() -> {
                start.await();
                return scenario.executor.execute(scenario.proposal);
            });
            start.countDown();
            boolean a = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            boolean b = second.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(a ^ b);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1L, scenario.executionCount());
        assertEquals(5L, scenario.activeCustodianCount());
        assertEquals(0, scenario.fixture.historyCount());
    }

}
