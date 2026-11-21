package org.odk.platform.openrosa;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Separate PostgreSQL connections prove the ordering of a snapshot
 * publication and an explicit project-membership revocation.
 *
 * Requires revocation writers to UPDATE the SAME rows locked by
 * JdbcEntityListSnapshotPermissionGate. This is not an app-level
 * enforcement or a production migration.
 */
@Testcontainers
class EntityListPostgresRevocationRaceIT {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID FORM = UUID.fromString("00000000-0000-0000-0000-000000000402");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000403");
    private static final UUID MANAGER = UUID.fromString("00000000-0000-0000-0000-000000000404");
    private static final UUID ROLE = UUID.fromString("00000000-0000-0000-0000-000000000405");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000406");
    private static final String ENTITY = "households.csv";
    private static final String ID = "a20f4e24-0a4c-4141-87aa-3c2a57ce1f27";
    private static final byte[] FIRST = ("name,label,__version\n" + ID + ",Before,1\n")
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEXT = ("name,label,__version\n" + ID + ",After,2\n")
            .getBytes(StandardCharsets.UTF_8);

    private static String md5(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(b));
    }

    private static final class Fixture {
        final PGSimpleDataSource data = new PGSimpleDataSource();
        final EntityListSnapshotLedgerCoordinator.Key key =
                new EntityListSnapshotLedgerCoordinator.Key(PROJECT, FORM, ENTITY);
        final JdbcEntityListSnapshotPermissionGate gate = new JdbcEntityListSnapshotPermissionGate();
        final JdbcEntityListSnapshotStore store;

        Fixture() throws Exception {
            String schema = "race_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection c = DriverManager.getConnection(
                    DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
                 var sql = c.createStatement()) {
                sql.execute("CREATE SCHEMA " + schema);
            }
            data.setUrl(DB.getJdbcUrl());
            data.setUser(DB.getUsername());
            data.setPassword(DB.getPassword());
            data.setCurrentSchema(schema);
            try (var stream = getClass().getResourceAsStream(
                    "/entity-list-ledger-schema-postgres.sql")) {
                assertNotNull(stream);
                String script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                try (Connection c = data.getConnection(); var sql = c.createStatement()) {
                    for (String part : script.split("(?m)^-- split\\s*$")) {
                        if (!part.isBlank()) {
                            sql.execute(part);
                        }
                    }
                    sql.execute("CREATE TABLE users(id UUID PRIMARY KEY, is_active BOOLEAN NOT NULL)");
                    sql.execute("CREATE TABLE projects(id UUID PRIMARY KEY, owner_id UUID NOT NULL, "
                            + "created_by_id UUID NOT NULL, is_active BOOLEAN NOT NULL)");
                    sql.execute("CREATE TABLE xforms(id UUID PRIMARY KEY, project_id UUID NOT NULL, "
                            + "is_active BOOLEAN NOT NULL)");
                    sql.execute("CREATE TABLE roles(id UUID PRIMARY KEY, name TEXT NOT NULL, "
                            + "is_active BOOLEAN NOT NULL)");
                    sql.execute("CREATE TABLE project_members(id UUID PRIMARY KEY, "
                            + "project_id UUID NOT NULL, user_id UUID NOT NULL, "
                            + "role_id UUID NOT NULL, is_active BOOLEAN NOT NULL)");
                    sql.execute("INSERT INTO users VALUES ('" + OWNER + "', TRUE), "
                            + "('" + MANAGER + "', TRUE)");
                    sql.execute("INSERT INTO projects VALUES ('" + PROJECT + "', '"
                            + OWNER + "', '" + OWNER + "', TRUE)");
                    sql.execute("INSERT INTO xforms VALUES ('" + FORM + "', '" + PROJECT + "', TRUE)");
                    sql.execute("INSERT INTO roles VALUES ('" + ROLE + "', 'PROJECT_MANAGER', TRUE)");
                    sql.execute("INSERT INTO project_members VALUES ('" + MEMBER + "', '"
                            + PROJECT + "', '" + MANAGER + "', '" + ROLE + "', TRUE)");
                }
            }
            var bootstrap = new JdbcEntityListSnapshotBootstrap(data,
                    (actor, scope) -> OWNER.equals(actor) && key.equals(scope));
            assertEquals(JdbcEntityListSnapshotBootstrap.Outcome.INITIALIZED,
                    bootstrap.initializeWithTransactionAudit(
                            OWNER, key, Map.of("odkMediaType", "entityList"),
                            FIRST, md5(FIRST), 1L, gate,
                            new JdbcEntityListSnapshotAuditWriter()));
            store = new JdbcEntityListSnapshotStore(data);
        }

        EntityListSnapshotLedgerCoordinator.Head next() throws Exception {
            var evidence = EntityListSnapshotEvidence.fromVerifiedBytes(
                    PROJECT, FORM, ENTITY, 2L, Map.of("odkMediaType", "entityList"),
                    NEXT, md5(NEXT), 1L).orElseThrow();
            return new EntityListSnapshotLedgerCoordinator.Head(evidence, NEXT);
        }

        long revision() {
            return store.currentHead(key).orElseThrow().evidence().revision();
        }

        long auditCount() throws SQLException {
            try (Connection c = data.getConnection();
                 var q = c.createStatement();
                 var rs = q.executeQuery("SELECT COUNT(*) FROM entity_list_snapshot_audit")) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }

        void revoke(Connection c) throws SQLException {
            try (var update = c.prepareStatement(
                    "UPDATE project_members SET is_active=FALSE WHERE id=?")) {
                update.setObject(1, MEMBER);
                assertEquals(1, update.executeUpdate());
            }
        }
    }

    @Test
    void revocationCommitBeforeLockedPermissionReadDeniesAppendWithoutAudit() throws Exception {
        var fixture = new Fixture();
        var original = fixture.store.currentHead(fixture.key).orElseThrow();
        var proposed = fixture.next();
        var pool = Executors.newSingleThreadExecutor();
        try (Connection revoker = fixture.data.getConnection()) {
            revoker.setAutoCommit(false);
            fixture.revoke(revoker); // Hold membership row lock while publisher starts.
            var started = new CountDownLatch(1);
            var future = pool.submit(() -> {
                started.countDown();
                return fixture.store.compareAndAppendAudited(
                        fixture.key, original, proposed, MANAGER,
                        fixture.gate, new JdbcEntityListSnapshotAuditWriter());
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            // Establish that permission SELECT ... FOR UPDATE is waiting on the
            // uncommitted row lock, not observing a stale pre-revocation grant.
            try (Connection observer = fixture.data.getConnection();
                 var watch = observer.prepareStatement(
                         "SELECT COUNT(*) FROM pg_stat_activity "
                                 + "WHERE wait_event_type='Lock' AND query LIKE ?")) {
                watch.setString(1, "%project_members%");
                boolean blocked = false;
                for (int i = 0; i < 60 && !blocked; i++) {
                    try (var rows = watch.executeQuery()) {
                        rows.next();
                        blocked = rows.getLong(1) > 0;
                    }
                    if (!blocked) {
                        Thread.sleep(50);
                    }
                }
                assertTrue(blocked, "publisher must wait on the membership revocation lock");
            }
            revoker.commit();
            assertFalse(future.get(15, TimeUnit.SECONDS));
            assertEquals(1L, fixture.revision());
            assertEquals(1L, fixture.auditCount()); // Only trusted initialization.
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void authorizedPublicationLocksMembershipUntilCommitThenRevocationProceeds() throws Exception {
        var fixture = new Fixture();
        var original = fixture.store.currentHead(fixture.key).orElseThrow();
        var proposed = fixture.next();
        var authorizedAndAuditing = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var publisher = pool.submit(() -> fixture.store.compareAndAppendAudited(
                    fixture.key, original, proposed, MANAGER, fixture.gate,
                    (connection, actor, scope, evidence) -> {
                        new JdbcEntityListSnapshotAuditWriter().recordAppended(
                                connection, actor, scope, evidence);
                        authorizedAndAuditing.countDown();
                        try {
                            if (!allowCommit.await(15, TimeUnit.SECONDS)) {
                                throw new SQLException("Test timed out awaiting audit commit");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new SQLException("Interrupted", e);
                        }
                    }));
            assertTrue(authorizedAndAuditing.await(15, TimeUnit.SECONDS));
            var revokerStarted = new CountDownLatch(1);
            var revocation = pool.submit(() -> {
                revokerStarted.countDown();
                try (Connection c = fixture.data.getConnection()) {
                    fixture.revoke(c);
                }
                return true;
            });
            assertTrue(revokerStarted.await(5, TimeUnit.SECONDS));
            // UPDATE must be blocked until the authorized publisher commits.
            try (Connection observer = fixture.data.getConnection();
                 var watch = observer.prepareStatement(
                         "SELECT COUNT(*) FROM pg_stat_activity "
                                 + "WHERE wait_event_type='Lock' "
                                 + "AND query LIKE 'UPDATE project_members%'")) {
                boolean blocked = false;
                for (int i = 0; i < 60 && !blocked; i++) {
                    try (var rows = watch.executeQuery()) {
                        rows.next();
                        blocked = rows.getLong(1) > 0;
                    }
                    if (!blocked) {
                        Thread.sleep(50);
                    }
                }
                assertTrue(blocked, "revocation should wait for publisher's permission lock");
            } finally {
                allowCommit.countDown();
            }
            assertTrue(publisher.get(15, TimeUnit.SECONDS));
            assertTrue(revocation.get(15, TimeUnit.SECONDS));
            assertEquals(2L, fixture.revision());
            assertEquals(2L, fixture.auditCount());
            // A later proposed update must not succeed after revocation.
            try (Connection verification = fixture.data.getConnection()) {
                verification.setAutoCommit(false);
                assertFalse(fixture.gate.mayInitialize(
                        verification, MANAGER, fixture.key));
                verification.rollback();
            }
        } finally {
            allowCommit.countDown();
            pool.shutdownNow();
        }
    }

}
