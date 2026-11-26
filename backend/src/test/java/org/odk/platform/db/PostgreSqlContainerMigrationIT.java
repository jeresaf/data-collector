package org.odk.platform.db;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import org.odk.platform.repository.SubmissionRepository;
import org.odk.platform.openrosa.JdbcEntityListSnapshotPermissionGate;
import org.odk.platform.openrosa.EntityListSnapshotLedgerCoordinator;
import javax.sql.DataSource;
import java.sql.Connection;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real production Flyway migration chain against PostgreSQL rather than
 * the H2 compatibility schema used by the normal "test" profile.
 *
 * This test is intentionally separate from the fast H2 suite. It certifies that:
 * - PostgreSQL itself can execute every production migration on a clean database.
 * - Hibernate's production mappings validate against the migrated schema.
 * - critical late migrations exist in the real PostgreSQL schema.
 *
 * Docker must be available. A missing/unreachable Docker engine is a test failure,
 * not a skipped certification, because the purpose of this test is to prove the
 * real-database gate.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.sql.init.mode=never",
                "spring.jpa.defer-datasource-initialization=false",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
                "spring.flyway.enabled=true",
                "spring.flyway.locations=filesystem:src/main/resources/db/migration",
                "spring.flyway.baseline-on-migrate=true",
                "spring.flyway.validate-on-migrate=true",
                "app.audit.async=false",
"app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "app.storage.local.path=${java.io.tmpdir}/odk-platform-postgres-it"
        }
)
@ActiveProfiles("postgres-it")
class PostgreSqlContainerMigrationIT {

    /**
     * Match the PostgreSQL major version used by the project's Docker Compose
     * development/runtime database.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("odk_platform")
                    .withUsername("odk_user")
                    .withPassword("odk_password");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
    }

    @org.springframework.boot.test.web.server.LocalServerPort
    int loopbackPort;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    SubmissionRepository submissionRepository;

    @Autowired
    DataSource dataSource;

    @Autowired
    org.odk.platform.service.ProjectMemberService projectMemberService;

    @Autowired
    org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @org.springframework.beans.factory.annotation.Value("${odk.storage.submissions-root:uploads/submissions}")
    String submissionsRoot;

    @Test
    void u08JournalRecoveryWaitsForLiveWriterBeforeRollback(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var journal = new org.odk.platform.service.SubmissionAttachmentJournal(jdbcTemplate, transactionManager);
        org.springframework.test.util.ReflectionTestUtils.setField(journal, "submissionsRoot", root.toString());
        org.springframework.test.util.ReflectionTestUtils.setField(journal, "enabled", true);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var ready = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pending = new java.util.concurrent.atomic.AtomicReference<
                org.odk.platform.service.SubmissionAttachmentJournal.Pending>();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = pool.submit(() -> tx.executeWithoutResult(status -> {
                try {
                    var p = journal.begin(UUID.randomUUID());
                    pending.set(p);
                    java.nio.file.Files.write(p.target(), new byte[]{1, 2, 3});
                    journal.forcePayload(p.target());
                    ready.countDown();
                    if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Writer release missing");
                    status.setRollbackOnly();
                } catch (Exception e) { throw new IllegalStateException(e); }
            }));
            assertThat(ready.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var recovery = pool.submit(journal::recoverOnce);
            // While the writer holds its transaction lock, recovery must not
            // remove either payload or marker. No reliance on exact scheduling:
            // verify the DB actually has a waiting row lock before release.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            boolean blocked = false;
            while (System.nanoTime() < deadline && !recovery.isDone()) {
                Integer n = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM pg_stat_activity
                        WHERE datname=current_database() AND wait_event_type='Lock'
                          AND query LIKE '%attachment_journal_locks%'
                        """, Integer.class);
                if (n != null && n > 0) { blocked = true; break; }
                Thread.sleep(50);
            }
            assertThat(blocked).as("recovery must acquire the same database lock").isTrue();
            assertThat(java.nio.file.Files.readAllBytes(pending.get().target())).containsExactly(new byte[]{1, 2, 3});
            assertThat(java.nio.file.Files.exists(pending.get().marker())).isTrue();
            release.countDown();
            writer.get(15, java.util.concurrent.TimeUnit.SECONDS);
            recovery.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(java.nio.file.Files.exists(pending.get().target())).isFalse();
            assertThat(java.nio.file.Files.exists(pending.get().marker())).isFalse();
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void u08JournalRecoversAfterWriterJvmIsForciblyTerminated(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var submission = UUID.randomUUID();
        var namespace = jdbcTemplate.queryForObject(
                "SELECT namespace FROM attachment_journal_identity WHERE id=1", UUID.class);
        var javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var command = new ProcessBuilder(javaExecutable, "-cp", classpath,
                AttachmentJournalCrashWriter.class.getName(), root.toString(), submission.toString());
        command.environment().put("U08_CRASH_JDBC_URL", POSTGRES.getJdbcUrl());
        command.environment().put("U08_CRASH_DB_USER", POSTGRES.getUsername());
        command.environment().put("U08_CRASH_DB_PASSWORD", POSTGRES.getPassword());
        command.redirectErrorStream(true);
        command.redirectOutput(root.resolve("writer.log").toFile());
        var child = command.start();
        var directory = root.resolve(submission.toString());
        var markers = root.resolve(".attachment-journal").resolve(namespace.toString());
        try {
            var ready = root.resolve("writer-ready");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (!java.nio.file.Files.exists(ready) && child.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(child.isAlive()).as("writer process must still own transaction").isTrue();
            assertThat(java.nio.file.Files.isRegularFile(ready)).isTrue();
            java.nio.file.Path payload;
            java.nio.file.Path marker;
            try (var files = java.nio.file.Files.list(directory);
                 var pendingFiles = java.nio.file.Files.list(markers)) {
                payload = files.findFirst().orElseThrow();
                marker = pendingFiles.findFirst().orElseThrow();
            }
            assertThat(java.nio.file.Files.readAllBytes(payload)).containsExactly(new byte[]{11, 22, 33});
            assertThat(java.nio.file.Files.exists(marker)).isTrue();
            child.destroyForcibly();
            assertThat(child.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isNotZero();
            // A hard kill bypassed Java transaction synchronization. Evidence
            // must remain until a separate process/context reconciles it.
            assertThat(java.nio.file.Files.exists(payload)).isTrue();
            assertThat(java.nio.file.Files.exists(marker)).isTrue();
            var recovery = new org.odk.platform.service.SubmissionAttachmentJournal(jdbcTemplate, transactionManager);
            org.springframework.test.util.ReflectionTestUtils.setField(recovery, "submissionsRoot", root.toString());
            org.springframework.test.util.ReflectionTestUtils.setField(recovery, "enabled", true);
            assertThat(recovery.recoverOnce()).isEqualTo(1);
            assertThat(java.nio.file.Files.exists(payload)).isFalse();
            assertThat(java.nio.file.Files.exists(marker)).isFalse();
            assertThat(recovery.recoverOnce()).isZero();
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void productionFlywayChainBuildsCurrentSchemaOnRealPostgreSql() {
        String databaseVersion =
                jdbcTemplate.queryForObject("select version()", String.class);

        assertThat(databaseVersion)
                .isNotBlank()
                .contains("PostgreSQL");

        Integer failedMigrations = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE success = FALSE
                """, Integer.class);

        assertThat(failedMigrations).isZero();

        Integer appliedMigrations = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE success = TRUE
                """, Integer.class);

        assertThat(appliedMigrations)
                .as("the production Flyway chain must actually run")
                .isGreaterThan(0);

        // Core schema.
        assertTableExists("users");
        assertTableExists("projects");
        assertTableExists("roles");
        assertTableExists("permissions");
        assertTableExists("app_users");
        assertTableExists("refresh_tokens");
        assertTableExists("email_verification_tokens");
        assertTableExists("password_reset_tokens");
        assertTableExists("xforms");
        assertTableExists("form_versions");
        assertTableExists("submissions");

        // Normalized submission/reporting schema.
        assertTableExists("submission_answers");
        assertTableExists("submission_attachments");
        assertTableExists("attachment_journal_identity");
        assertTableExists("attachment_journal_locks");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attachment_journal_locks", Integer.class)).isEqualTo(32);
        assertTableExists("submission_events");
        assertTableExists("report_definitions");
        assertTableExists("reference_lists");

        // Administration/audit additions introduced after the original schema.
        assertTableExists("audit_logs");
        assertTableExists("audit_log_details");
        assertTableExists("web_form_assignments");

        // AUDIT-02: one app user may have several different form assignments
        // inside the same project.
        assertConstraintExists("uk_app_user_projects_user_project_form");

        // AUDIT-08: browser assignments use their own user/role assignment model.
        assertConstraintExists("uq_web_form_assignments_target");

        // TASK 11.9 / AUDIT-09 dependency: respondent attachments persist their
        // concrete normalized answer relationship.
        assertColumnExists("submission_attachments", "answer_id");
        assertConstraintExists("fk_submission_attachments_answer");

        // Audit entity/schema alignment that previously differed between H2 and
        // PostgreSQL.
        assertColumnExists("audit_logs", "resource_type");
        assertColumnExists("audit_logs", "resource_id");
        assertColumnExists("audit_logs", "request_id");
        assertColumnExists("audit_logs", "session_id");
        assertColumnExists("audit_logs", "metadata");

        // Prove PostgreSQL-native JSON storage survived the production chain.
        assertColumnType("audit_logs", "metadata", "jsonb");

        // Auth/audit network context is intentionally stored as bounded strings,
        // matching the JPA entities rather than PostgreSQL-specific INET/TEXT types.
        assertColumnType("audit_logs", "ip_address", "character varying");
        assertColumnType("audit_logs", "user_agent", "character varying");
        assertColumnType("refresh_tokens", "ip_address", "character varying");
        assertColumnType("refresh_tokens", "user_agent", "character varying");
    }


    @Test
    void submissionQueriesAcceptNullOptionalDateFiltersOnRealPostgreSql() {
        assertThat(submissionRepository.findSubmissions(
                null, null, null, null, null, null, null, null,
                PageRequest.of(0, 20)))
                .isNotNull();

        assertThat(submissionRepository.countSubmissions(
                null, null, null, null, null, null, null, null))
                .isGreaterThanOrEqualTo(0L);
    }

    @Test
    void seedDataRunsOnPostgreSqlAndCreatesSystemAdmin() {
        Integer adminUsers = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM users
                WHERE username = 'admin'
                  AND is_active = TRUE
                """, Integer.class);

        assertThat(adminUsers)
                .as("the PostgreSQL seed migration must create the development admin")
                .isEqualTo(1);

        Integer systemAdminMemberships = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM user_roles ur
                JOIN users u ON u.id = ur.user_id
                JOIN roles r ON r.id = ur.role_id
                WHERE u.username = 'admin'
                  AND r.name = 'SYSTEM_ADMIN'
                """, Integer.class);

        assertThat(systemAdminMemberships)
                .as("the seeded admin must receive SYSTEM_ADMIN")
                .isEqualTo(1);
    }


    @Test
    void snapshotAuthorizationUsesFullMigratedProjectAndRoleTables() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID formId = UUID.randomUUID();
        UUID managerId = UUID.randomUUID();
        UUID foreignProject = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        String unique = UUID.randomUUID().toString().replace("-", "");
        var gate = new JdbcEntityListSnapshotPermissionGate();
        var key = new EntityListSnapshotLedgerCoordinator.Key(projectId, formId, "households.csv");

        // The entire production Flyway chain has already run in this container.
        // The test uses one uncommitted transaction and rolls back all fixtures.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                UUID owner;
                try (var query = connection.prepareStatement(
                        "SELECT id FROM users WHERE username='admin' AND is_active=TRUE");
                     var rows = query.executeQuery()) {
                    org.junit.jupiter.api.Assertions.assertTrue(rows.next());
                    owner = rows.getObject(1, UUID.class);
                }
                try (var create = connection.prepareStatement(
                        "INSERT INTO users (id,username,email,password_hash) VALUES (?,?,?,?)")) {
                    create.setObject(1, managerId);
                    create.setString(2, "snapshot_manager_" + unique);
                    create.setString(3, unique + "@test.invalid");
                    create.setString(4, "test-only");
                    create.executeUpdate();
                }
                try (var create = connection.prepareStatement(
                        "INSERT INTO projects (id,name,owner_id,created_by_id) VALUES (?,?,?,?)")) {
                    create.setObject(1, projectId);
                    create.setString(2, "Snapshot authorization migration gate");
                    create.setObject(3, owner);
                    create.setObject(4, owner);
                    create.executeUpdate();
                    create.setObject(1, foreignProject);
                    create.setString(2, "Snapshot foreign project");
                    create.executeUpdate();
                }
                try (var create = connection.prepareStatement(
                        "INSERT INTO xforms (id,title,project_id,created_by_id) VALUES (?,?,?,?)")) {
                    create.setObject(1, formId);
                    create.setString(2, "Snapshot validation form");
                    create.setObject(3, projectId);
                    create.setObject(4, owner);
                    create.executeUpdate();
                }
                // Real project-manager role may already be seeded. Use a
                // distinct project-specific role only if the global name is absent.
                try (var query = connection.prepareStatement(
                        "SELECT id FROM roles WHERE name='PROJECT_MANAGER'");
                     var rows = query.executeQuery()) {
                    if (rows.next()) {
                        roleId = rows.getObject(1, UUID.class);
                    } else {
                        try (var create = connection.prepareStatement(
                                "INSERT INTO roles(id,name) VALUES (?, 'PROJECT_MANAGER')")) {
                            create.setObject(1, roleId);
                            create.executeUpdate();
                        }
                    }
                }
                try (var create = connection.prepareStatement(
                        "INSERT INTO project_members (id,project_id,user_id,role_id) VALUES (?,?,?,?)")) {
                    create.setObject(1, UUID.randomUUID());
                    create.setObject(2, projectId);
                    create.setObject(3, managerId);
                    create.setObject(4, roleId);
                    create.executeUpdate();
                }

                org.junit.jupiter.api.Assertions.assertTrue(
                        gate.mayInitialize(connection, owner, key));
                org.junit.jupiter.api.Assertions.assertTrue(
                        gate.mayInitialize(connection, managerId, key));
                org.junit.jupiter.api.Assertions.assertFalse(
                        gate.mayInitialize(connection, managerId,
                                new EntityListSnapshotLedgerCoordinator.Key(
                                        foreignProject, formId, "households.csv")));

                try (var update = connection.prepareStatement(
                        "UPDATE project_members SET is_active=FALSE "
                                + "WHERE project_id=? AND user_id=?")) {
                    update.setObject(1, projectId);
                    update.setObject(2, managerId);
                    update.executeUpdate();
                }
                org.junit.jupiter.api.Assertions.assertFalse(
                        gate.mayInitialize(connection, managerId, key));
                try (var update = connection.prepareStatement(
                        "UPDATE xforms SET is_active=FALSE WHERE id=?")) {
                    update.setObject(1, formId);
                    update.executeUpdate();
                }
                org.junit.jupiter.api.Assertions.assertFalse(
                        gate.mayInitialize(connection, owner, key));
            } finally {
                connection.rollback();
            }
        }
    }


    @Test
    void springTransactionalMemberRemovalWaitsForAuthorizedPublisherOnMigratedSchema()
            throws Exception {
        // This uses the actual Spring proxy + production Flyway tables, not
        // the manually constructed service from isolated PostgreSQL tests.
        org.junit.jupiter.api.Assertions.assertTrue(
                org.springframework.aop.support.AopUtils.isAopProxy(projectMemberService));
        UUID owner = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username='admin'", UUID.class);
        UUID role = jdbcTemplate.queryForObject(
                "SELECT id FROM roles WHERE name='PROJECT_MANAGER'", UUID.class);
        UUID manager = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID form = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var key = new EntityListSnapshotLedgerCoordinator.Key(project, form, "households.csv");

        jdbcTemplate.update(
                "INSERT INTO users (id,username,email,password_hash) VALUES (?,?,?,?)",
                manager, "u05_proxy_" + suffix, suffix + "@test.invalid", "test-only");
        try {
            jdbcTemplate.update(
                    "INSERT INTO projects (id,name,owner_id,created_by_id) VALUES (?,?,?,?)",
                    project, "U05 transactional race " + suffix, owner, owner);
            jdbcTemplate.update(
                    "INSERT INTO xforms (id,title,project_id,created_by_id) VALUES (?,?,?,?)",
                    form, "U05 test form", project, owner);
            jdbcTemplate.update(
                    "INSERT INTO project_members(id,project_id,user_id,role_id) VALUES (?,?,?,?)",
                    membership, project, manager, role);
            var gate = new JdbcEntityListSnapshotPermissionGate();
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try (Connection publishing = dataSource.getConnection()) {
                publishing.setAutoCommit(false);
                try {
                    // Hold the same locked authorization rows that audited
                    // publication holds from permission check through commit.
                    org.junit.jupiter.api.Assertions.assertTrue(
                            gate.mayInitialize(publishing, manager, key));
                    var started = new java.util.concurrent.CountDownLatch(1);
                    var revoker = pool.submit(() -> {
                        started.countDown();
                        projectMemberService.removeMember(project, membership);
                        return true;
                    });
                    org.junit.jupiter.api.Assertions.assertTrue(
                            started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    boolean blocked = false;
                    for (int attempt = 0; attempt < 80 && !blocked; attempt++) {
                        Integer waiting = jdbcTemplate.queryForObject("""
                                SELECT COUNT(*) FROM pg_stat_activity
                                 WHERE wait_event_type='Lock'
                                   AND query LIKE '%UPDATE project_members%'
                                """, Integer.class);
                        blocked = waiting != null && waiting > 0;
                        if (!blocked) {
                            Thread.sleep(50);
                        }
                    }
                    org.junit.jupiter.api.Assertions.assertTrue(blocked,
                            "Spring-managed membership removal should wait for publication lock");
                    publishing.commit();
                    org.junit.jupiter.api.Assertions.assertTrue(
                            revoker.get(15, java.util.concurrent.TimeUnit.SECONDS));
                    try (Connection after = dataSource.getConnection()) {
                        after.setAutoCommit(false);
                        org.junit.jupiter.api.Assertions.assertFalse(
                                gate.mayInitialize(after, manager, key));
                        after.rollback();
                    }
                } finally {
                    publishing.rollback();
                    pool.shutdownNow();
                }
            }
        } finally {
            // All fixture data is synthetic and confined to this disposable
            // PostgreSQL Testcontainer. Clean dependent tables in FK order.
            jdbcTemplate.update("DELETE FROM project_members WHERE id=?", membership);
            jdbcTemplate.update("DELETE FROM xforms WHERE id=?", form);
            jdbcTemplate.update("DELETE FROM projects WHERE id=?", project);
            jdbcTemplate.update("DELETE FROM users WHERE id=?", manager);
        }
    }


    @Test
    void snapshotPermissionLocksGuardOtherRevocationWritersOnMigratedSchema()
            throws Exception {
        // All tables below come from the complete production Flyway chain.
        // Only disposable test rows are committed. Global role deactivation
        // is tested inside a rolled-back transaction.
        UUID owner = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username='admin'", UUID.class);
        UUID managerRole = jdbcTemplate.queryForObject(
                "SELECT id FROM roles WHERE name='PROJECT_MANAGER'", UUID.class);
        UUID viewerRole = jdbcTemplate.queryForObject(
                "SELECT id FROM roles WHERE name='VIEWER'", UUID.class);
        UUID actor = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID form = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        String unique = UUID.randomUUID().toString().replace("-", "");
        var key = new EntityListSnapshotLedgerCoordinator.Key(project, form, "households.csv");
        var gate = new JdbcEntityListSnapshotPermissionGate();

        jdbcTemplate.update("INSERT INTO users (id,username,email,password_hash) VALUES (?,?,?,?)",
                actor, "u05_rev_" + unique, unique + "@test.invalid", "test-only");
        try {
            jdbcTemplate.update(
                    "INSERT INTO projects (id,name,owner_id,created_by_id) VALUES (?,?,?,?)",
                    project, "U05 row-lock test " + unique, owner, owner);
            jdbcTemplate.update(
                    "INSERT INTO xforms (id,title,project_id,created_by_id) VALUES (?,?,?,?)",
                    form, "U05 form", project, owner);
            jdbcTemplate.update(
                    "INSERT INTO project_members(id,project_id,user_id,role_id) VALUES (?,?,?,?)",
                    member, project, actor, managerRole);

            try (Connection publisher = dataSource.getConnection();
                 Connection revoker = dataSource.getConnection()) {
                publisher.setAutoCommit(false);
                revoker.setAutoCommit(false);
                try {
                    org.junit.jupiter.api.Assertions.assertTrue(
                            gate.mayInitialize(publisher, actor, key));

                    // A revocation writer on a different connection cannot
                    // change any of the four locked authority sources while
                    // a publisher has acquired its in-transaction permission.
                    assertRevocationBlocked(revoker,
                            "UPDATE users SET is_active=FALSE WHERE id=?", actor);
                    assertRevocationBlocked(revoker,
                            "UPDATE projects SET is_active=FALSE WHERE id=?", project);
                    assertRevocationBlocked(revoker,
                            "UPDATE project_members SET role_id=? WHERE id=?",
                            viewerRole, member);
                    assertRevocationBlocked(revoker,
                            "UPDATE roles SET is_active=FALSE WHERE id=?", managerRole);
                } finally {
                    publisher.rollback();
                    revoker.rollback();
                }
            }

            // Each committed revocation must cause the gate to deny new
            // authorization on an independent transaction.
            jdbcTemplate.update("UPDATE users SET is_active=FALSE WHERE id=?", actor);
            assertSnapshotPermissionDenied(gate, actor, key);
            jdbcTemplate.update("UPDATE users SET is_active=TRUE WHERE id=?", actor);

            jdbcTemplate.update("UPDATE projects SET is_active=FALSE WHERE id=?", project);
            assertSnapshotPermissionDenied(gate, actor, key);
            jdbcTemplate.update("UPDATE projects SET is_active=TRUE WHERE id=?", project);

            jdbcTemplate.update("UPDATE project_members SET role_id=? WHERE id=?",
                    viewerRole, member);
            assertSnapshotPermissionDenied(gate, actor, key);
            jdbcTemplate.update("UPDATE project_members SET role_id=? WHERE id=?",
                    managerRole, member);

            // Do not commit a change to a globally seeded role, even inside
            // the disposable database. A same-connection transactional read
            // confirms denial and then restores the role by rollback.
            try (Connection roleChange = dataSource.getConnection()) {
                roleChange.setAutoCommit(false);
                try (var sql = roleChange.prepareStatement(
                        "UPDATE roles SET is_active=FALSE WHERE id=?")) {
                    sql.setObject(1, managerRole);
                    org.junit.jupiter.api.Assertions.assertEquals(1, sql.executeUpdate());
                }
                org.junit.jupiter.api.Assertions.assertFalse(
                        gate.mayInitialize(roleChange, actor, key));
                roleChange.rollback();
            }
            assertSnapshotPermissionAllowed(gate, actor, key);
        } finally {
            jdbcTemplate.update("DELETE FROM project_members WHERE id=?", member);
            jdbcTemplate.update("DELETE FROM xforms WHERE id=?", form);
            jdbcTemplate.update("DELETE FROM projects WHERE id=?", project);
            jdbcTemplate.update("DELETE FROM users WHERE id=?", actor);
        }
    }

    private void assertRevocationBlocked(Connection revoker, String sql, Object... args)
            throws Exception {
        // PostgreSQL returns 55P03 on lock_timeout while trying to UPDATE
        // any row already locked by the publisher's authorization gate.
        try (var timeout = revoker.createStatement()) {
            timeout.execute("SET LOCAL lock_timeout='300ms'");
        }
        try (var update = revoker.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                update.setObject(i + 1, args[i]);
            }
            java.sql.SQLException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    java.sql.SQLException.class, update::executeUpdate);
            org.junit.jupiter.api.Assertions.assertEquals("55P03", failure.getSQLState());
        }
        // lock_timeout leaves this transaction aborted. Clear it before the
        // next independent blocked update, without touching publisher locks.
        revoker.rollback();
    }

    private void assertSnapshotPermissionDenied(
            JdbcEntityListSnapshotPermissionGate gate, UUID actor,
            EntityListSnapshotLedgerCoordinator.Key key) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            org.junit.jupiter.api.Assertions.assertFalse(
                    gate.mayInitialize(connection, actor, key));
            connection.rollback();
        }
    }

    private void assertSnapshotPermissionAllowed(
            JdbcEntityListSnapshotPermissionGate gate, UUID actor,
            EntityListSnapshotLedgerCoordinator.Key key) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            org.junit.jupiter.api.Assertions.assertTrue(
                    gate.mayInitialize(connection, actor, key));
            connection.rollback();
        }
    }

    private void assertTableExists(String tableName) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT to_regclass('public.' || ?) IS NOT NULL
                """, Boolean.class, tableName);

        assertThat(exists)
                .as("PostgreSQL table %s should exist", tableName)
                .isTrue();
    }

    private void assertColumnExists(String tableName, String columnName) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = ?
                      AND column_name = ?
                )
                """, Boolean.class, tableName, columnName);

        assertThat(exists)
                .as("PostgreSQL column %s.%s should exist", tableName, columnName)
                .isTrue();
    }

    private void assertColumnType(
            String tableName,
            String columnName,
            String expectedDataType) {

        String actualType = jdbcTemplate.queryForObject("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND column_name = ?
                """, String.class, tableName, columnName);

        assertThat(actualType)
                .as("PostgreSQL column %s.%s type", tableName, columnName)
                .isEqualTo(expectedDataType);
    }

    private void assertConstraintExists(String constraintName) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_constraint
                    WHERE conname = ?
                )
                """, Boolean.class, constraintName);

        assertThat(exists)
                .as("PostgreSQL constraint %s should exist", constraintName)
                .isTrue();
    }
    @Test
    void v1ToV2ReplacementArchivesExactSourceVersionAndXmlInPostgreSql()
            throws Exception {
        // Real production Flyway schema; no live database, no HTTP identity.
        UUID owner = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username='admin' LIMIT 1", UUID.class);
        assertThat(owner).isNotNull();
        UUID project = java.util.UUID.randomUUID();
        UUID form = java.util.UUID.randomUUID();
        UUID v1 = java.util.UUID.randomUUID();
        UUID v2 = java.util.UUID.randomUUID();
        UUID sourceId = java.util.UUID.randomUUID();
        String sourceInstance = "uuid:u06-source-" + sourceId;
        String targetInstance = "uuid:u06-revised-" + sourceId;
        String sourceXml = "<data id=\"u06\" version=\"1\"><name>Original</name></data>";
        String targetXml = "<data id=\"u06\" version=\"2\"><name>Revised</name></data>";
        try {
            jdbcTemplate.update(
                    "INSERT INTO projects(id,name,owner_id,created_by_id) VALUES (?,?,?,?)",
                    project,"U06 PostgreSQL synthetic",owner,owner);
            jdbcTemplate.update(
                    "INSERT INTO xforms(id,title,xmlns,project_id,created_by_id,is_published) "
                            + "VALUES (?,?,?,?,?,TRUE)",
                    form,"U06 versioned form","u06",project,owner);
            jdbcTemplate.update(
                    "INSERT INTO form_versions(id,form_id,version,xml_content,xmlns,"
                            + "created_by_id,is_published,is_latest) "
                            + "VALUES (?,?,?,?,?,?,TRUE,FALSE)",
                    v1,form,"1","<form-v1/>","u06",owner);
            jdbcTemplate.update(
                    "INSERT INTO form_versions(id,form_id,version,xml_content,xmlns,"
                            + "created_by_id,is_published,is_latest) "
                            + "VALUES (?,?,?,?,?,?,TRUE,TRUE)",
                    v2,form,"2","<form-v2/>","u06",owner);
            jdbcTemplate.update(
                    "INSERT INTO submissions(id,form_id,form_version_id,instance_id,xml_content,"
                            + "submitted_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",
                    sourceId,form,v1,sourceInstance,sourceXml);
            // Exercise the real archive SQL in SubmissionReplacementService
            // against the migrated PostgreSQL schema; repository save is
            // mocked so the test isolates the historical archive boundary.
            var repository = org.mockito.Mockito.mock(
                    org.odk.platform.repository.SubmissionRepository.class);
            var service = new org.odk.platform.service.SubmissionReplacementService(
                    jdbcTemplate,repository);
            var source = org.odk.platform.domain.Submission.builder()
                    .id(sourceId).instanceId(sourceInstance)
                    .xmlContent(sourceXml).status("SUBMITTED")
                    .formVersion(org.odk.platform.domain.FormVersion.builder().id(v1).build())
                    .submittedAt(java.time.Instant.now()).build();
            var next = org.odk.platform.domain.Submission.builder()
                    .instanceId(targetInstance).xmlContent(targetXml)
                    .formVersion(org.odk.platform.domain.FormVersion.builder().id(v2).build())
                    .build();
            org.mockito.Mockito.when(repository.saveAndFlush(next)).thenReturn(next);
            org.junit.jupiter.api.Assertions.assertSame(next,
                    service.createReplacement(source,next,sourceInstance));

            var history = jdbcTemplate.queryForMap(
                    "SELECT submission_id,version_number,instance_id,xml_content,"
                            + "source_form_version_id,deprecated_id "
                            + "FROM submission_versions WHERE submission_id=?",
                    sourceId);
            assertThat(history.get("submission_id")).isEqualTo(sourceId);
            assertThat(((Number) history.get("version_number")).intValue()).isEqualTo(1);
            assertThat(history.get("instance_id")).isEqualTo(sourceInstance);
            assertThat(history.get("xml_content")).isEqualTo(sourceXml);
            assertThat(history.get("source_form_version_id")).isEqualTo(v1);
            assertThat(history.get("deprecated_id")).isEqualTo(sourceInstance);

            var persistedOriginal = jdbcTemplate.queryForMap(
                    "SELECT instance_id,xml_content,form_version_id "
                            + "FROM submissions WHERE id=?",sourceId);
            assertThat(persistedOriginal.get("instance_id")).isEqualTo(sourceInstance);
            assertThat(persistedOriginal.get("xml_content")).isEqualTo(sourceXml);
            assertThat(persistedOriginal.get("form_version_id")).isEqualTo(v1);

            // A second archive of the same source advances version number
            // without destroying historical evidence.
            service.createReplacement(source,next,sourceInstance);
            var historyVersions = jdbcTemplate.queryForList(
                    "SELECT version_number FROM submission_versions "
                            + "WHERE submission_id=? ORDER BY version_number",Integer.class,sourceId);
            assertThat(historyVersions).containsExactly(1,2);
        } finally {
            jdbcTemplate.update("DELETE FROM submission_versions WHERE submission_id=?",sourceId);
            jdbcTemplate.update("DELETE FROM submissions WHERE id=?",sourceId);
            jdbcTemplate.update("DELETE FROM form_versions WHERE id IN (?,?)",v1,v2);
            jdbcTemplate.update("DELETE FROM xforms WHERE id=?",form);
            jdbcTemplate.update("DELETE FROM projects WHERE id=?",project);
        }
    }

    @Test
    void followUpWorkPersistsDistinctSubmissionAndDurableRecordLinkOnPostgreSql(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path testFiles)
            throws Exception {
        UUID owner = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username='admin' LIMIT 1", UUID.class);
        UUID project = UUID.randomUUID();
        UUID form = UUID.randomUUID();
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID workId = UUID.randomUUID();
        UUID failedWorkId = UUID.randomUUID();
        UUID amendmentWorkId = UUID.randomUUID();
        UUID appUserId = UUID.randomUUID();
        String originalInstance = "uuid:u06-first-" + sourceId;
        String secondInstance = "uuid:u06-second-" + childId;
        String originalXml = "<data id=\"u06\" version=\"1\"><visit>1</visit></data>";
        String secondXml = "<data id=\"u06\" version=\"2\"><visit>2</visit></data>";
        try {
            jdbcTemplate.update(
                    "INSERT INTO projects(id,name,owner_id,created_by_id) VALUES (?,?,?,?)",
                    project, "U06 linked visits", owner, owner);
            jdbcTemplate.update(
                    "INSERT INTO xforms(id,title,xmlns,project_id,created_by_id,is_published) "
                            + "VALUES (?,?,?,?,?,TRUE)",
                    form, "U06 visits", "u06-visits", project, owner);
            jdbcTemplate.update(
                    "INSERT INTO form_versions(id,form_id,version,xml_content,xmlns,"
                            + "created_by_id,is_published,is_latest) "
                            + "VALUES (?,?,?,?,?,?,TRUE,FALSE)",
                    v1, form, "1", "<form-v1/>", "u06-visits", owner);
            jdbcTemplate.update(
                    "INSERT INTO form_versions(id,form_id,version,xml_content,xmlns,"
                            + "created_by_id,is_published,is_latest) "
                            + "VALUES (?,?,?,?,?,?,TRUE,TRUE)",
                    v2, form, "2", "<form-v2/>", "u06-visits", owner);
            jdbcTemplate.update(
                    "INSERT INTO app_users (id,username,email,password_hash,full_name,"
                            + "is_active,is_verified) VALUES (?,?,?,?,?,TRUE,TRUE)",
                    appUserId, "u06-" + appUserId, appUserId + "@example.test",
                    "not-used", "U06 Test");
            jdbcTemplate.update(
                    "INSERT INTO submissions(id,form_id,form_version_id,instance_id,"
                            + "xml_content,submitted_at,submitted_by_id) "
                            + "VALUES (?,?,?,?,?,CURRENT_TIMESTAMP,?)",
                    sourceId, form, v1, originalInstance, originalXml, appUserId);
            jdbcTemplate.update(
                    "INSERT INTO submissions(id,form_id,form_version_id,instance_id,"
                            + "xml_content,submitted_at,submitted_by_id) "
                            + "VALUES (?,?,?,?,?,CURRENT_TIMESTAMP,?)",
                    childId, form, v2, secondInstance, secondXml, appUserId);
            jdbcTemplate.update(
                    "INSERT INTO record_work_items(id,project_id,source_submission_id,"
                            + "source_form_version_id,source_instance_id,target_form_version_id,"
                            + "assigned_app_user_id,work_type,state,client_request_id,visit_key) "
                            + "VALUES (?,?,?,?,?,?,?,'FOLLOW_UP','READY',?,?)",
                    workId, project, sourceId, v1, originalInstance, v2, appUserId,
                    "u06-work-" + workId, "second-visit");

            var actor = org.odk.platform.domain.AppUser.builder().id(appUserId).build();
            var projectEntity = org.odk.platform.domain.Project.builder().id(project).build();
            var formEntity = org.odk.platform.domain.XForm.builder()
                    .id(form).project(projectEntity).build();
            var sourceEntity = org.odk.platform.domain.Submission.builder()
                    .id(sourceId).form(formEntity).submittedBy(actor)
                    .instanceId(originalInstance).xmlContent(originalXml).build();
            var childEntity = org.odk.platform.domain.Submission.builder()
                    .id(childId).form(formEntity).submittedBy(actor)
                    .instanceId(secondInstance).xmlContent(secondXml).build();
            var target = org.odk.platform.domain.FormVersion.builder().id(v2).build();
            var sources = org.mockito.Mockito.mock(
                    org.odk.platform.repository.SubmissionRepository.class);
            org.mockito.Mockito.when(sources.findByIdWithForm(sourceId))
                    .thenReturn(java.util.Optional.of(sourceEntity));
            var assignments = org.mockito.Mockito.mock(
                    org.odk.platform.repository.AppUserProjectRepository.class);
            org.mockito.Mockito.when(assignments.findAssignedFormIdsByAppUserIdAndProjectId(
                    appUserId, project)).thenReturn(java.util.List.of(form));
            var work = new org.odk.platform.service.RecordWorkService(
                    jdbcTemplate,
                    org.mockito.Mockito.mock(org.odk.platform.security.CurrentUserService.class),
                    org.mockito.Mockito.mock(org.odk.platform.repository.ProjectRepository.class),
                    sources,
                    org.mockito.Mockito.mock(org.odk.platform.repository.FormVersionRepository.class),
                    assignments);

            work.completeIfPresent(project, actor, null, childEntity, target, workId.toString());

            var item = jdbcTemplate.queryForMap(
                    "SELECT state,completed_submission_id FROM record_work_items WHERE id=?",
                    workId);
            assertThat(item.get("state")).isEqualTo("COMPLETED");
            assertThat(item.get("completed_submission_id")).isEqualTo(childId);

            var link = jdbcTemplate.queryForMap(
                    "SELECT source_submission_id,child_submission_id,work_item_id,"
                            + "link_type,visit_key FROM record_links WHERE work_item_id=?",
                    workId);
            assertThat(link.get("source_submission_id")).isEqualTo(sourceId);
            assertThat(link.get("child_submission_id")).isEqualTo(childId);
            assertThat(link.get("work_item_id")).isEqualTo(workId);
            assertThat(link.get("link_type")).isEqualTo("FOLLOW_UP");
            assertThat(link.get("visit_key")).isEqualTo("second-visit");
            assertThat(sourceId).isNotEqualTo(childId);
            assertThat(originalInstance).isNotEqualTo(secondInstance);
            var unchangedSource = jdbcTemplate.queryForMap(
                    "SELECT instance_id,xml_content,form_version_id FROM submissions WHERE id=?",
                    sourceId);
            assertThat(unchangedSource.get("instance_id")).isEqualTo(originalInstance);
            assertThat(unchangedSource.get("xml_content")).isEqualTo(originalXml);
            assertThat(unchangedSource.get("form_version_id")).isEqualTo(v1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM submissions WHERE id IN (?,?)",
                    Integer.class, sourceId, childId)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM submission_versions WHERE submission_id=?",
                    Integer.class, sourceId)).isZero();

            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.web.server.ResponseStatusException.class,
                    () -> work.completeIfPresent(
                            project, actor, null, childEntity, target, workId.toString()));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM record_links WHERE work_item_id=?",
                    Integer.class, workId)).isEqualTo(1);

            // A different READY follow-up work item incorrectly points to an
            // already-linked child. The work-item UPDATE succeeds first;
            // record_links(child_submission_id UNIQUE) must reject insertion.
            // Verify the Spring transaction rolls back the UPDATE as well.
            jdbcTemplate.update(
                    "INSERT INTO record_work_items(id,project_id,source_submission_id,"
                            + "source_form_version_id,source_instance_id,target_form_version_id,"
                            + "assigned_app_user_id,work_type,state,client_request_id,visit_key) "
                            + "VALUES (?,?,?,?,?,?,?,'FOLLOW_UP','READY',?,?)",
                    failedWorkId, project, sourceId, v1, originalInstance, v2, appUserId,
                    "u06-work-" + failedWorkId, "third-visit");
            var transaction = new org.springframework.transaction.support.TransactionTemplate(
                    transactionManager);
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () -> transaction.executeWithoutResult(ignored ->
                            work.completeIfPresent(project, actor, null, childEntity,
                                    target, failedWorkId.toString())));
            var failedItem = jdbcTemplate.queryForMap(
                    "SELECT state,completed_submission_id "
                            + "FROM record_work_items WHERE id=?", failedWorkId);
            assertThat(failedItem.get("state")).isEqualTo("READY");
            assertThat(failedItem.get("completed_submission_id")).isNull();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM record_links WHERE work_item_id=?",
                    Integer.class, failedWorkId)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM record_links WHERE child_submission_id=?",
                    Integer.class, childId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM submissions WHERE id IN (?,?)",
                    Integer.class, sourceId, childId)).isEqualTo(2);
            // The prior duplicate-link failure tests UPDATE+INSERT atomicity.
            // Now ensure a proposed visit and its attachment do not survive
            // an invalid work-item completion in the same transaction.
            UUID provisional = UUID.randomUUID();
            UUID provisionalMedia = UUID.randomUUID();
            var candidate = org.odk.platform.domain.Submission.builder()
                    .id(provisional).instanceId("uuid:" + provisional).build();
            var wrongVersion = org.odk.platform.domain.FormVersion.builder().id(v1).build();
            var tx = new org.springframework.transaction.support.TransactionTemplate(
                    transactionManager);
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.web.server.ResponseStatusException.class,
                    () -> tx.executeWithoutResult(status -> {
                        jdbcTemplate.update(
                                "INSERT INTO submissions(id,form_id,form_version_id,instance_id,"
                                        + "xml_content,submitted_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",
                                provisional, form, v2, "uuid:" + provisional, "<visit>3</visit>");
                        jdbcTemplate.update(
                                "INSERT INTO submission_attachments(id,submission_id,file_name,"
                                        + "storage_path) VALUES (?,?,?,?)",
                                provisionalMedia, provisional, "test.jpg", "synthetic/test.jpg");
                        work.completeIfPresent(project, actor, null, candidate,
                                wrongVersion, failedWorkId.toString());
                    }));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM submissions WHERE id=?", Integer.class, provisional))
                    .isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM submission_attachments WHERE id=?",
                    Integer.class, provisionalMedia)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT state FROM record_work_items WHERE id=?", String.class,
                    failedWorkId)).isEqualTo("READY");

            // The server owns the distinction between AMENDMENT (must replace
            // precisely its source) and FOLLOW_UP (must create a new record).
            // A client must not switch workflows just by altering deprecatedID.
            var followUpMisclassifiedAsReplacement =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            org.springframework.web.server.ResponseStatusException.class,
                            () -> tx.executeWithoutResult(ignored ->
                                    work.completeIfPresent(project, actor, sourceEntity,
                                            childEntity, target, failedWorkId.toString())));
            assertThat(followUpMisclassifiedAsReplacement.getStatusCode().value())
                    .isEqualTo(409);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT state FROM record_work_items WHERE id=?", String.class,
                    failedWorkId)).isEqualTo("READY");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM record_links WHERE work_item_id=?",
                    Integer.class, failedWorkId)).isZero();

            jdbcTemplate.update(
                    "INSERT INTO record_work_items(id,project_id,source_submission_id,"
                            + "source_form_version_id,source_instance_id,target_form_version_id,"
                            + "assigned_app_user_id,work_type,state,client_request_id) "
                            + "VALUES (?,?,?,?,?,?,?,'AMENDMENT','READY',?)",
                    amendmentWorkId, project, sourceId, v1, originalInstance, v2,
                    appUserId, "u06-work-" + amendmentWorkId);
            var amendmentMisclassifiedAsNewVisit =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            org.springframework.web.server.ResponseStatusException.class,
                            () -> tx.executeWithoutResult(ignored ->
                                    work.completeIfPresent(project, actor, null,
                                            childEntity, target, amendmentWorkId.toString())));
            assertThat(amendmentMisclassifiedAsNewVisit.getStatusCode().value())
                    .isEqualTo(409);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT state FROM record_work_items WHERE id=?", String.class,
                    amendmentWorkId)).isEqualTo("READY");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM record_links WHERE work_item_id=?",
                    Integer.class, amendmentWorkId)).isZero();

            // Both invalid routes leave the committed original and valid
            // follow-up intact, with no synthetic replacement-history entry.
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM record_links WHERE work_item_id=?",
                    Integer.class, workId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM submissions WHERE id IN (?,?)",
                    Integer.class, sourceId, childId)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM submission_versions WHERE submission_id=?",
                    Integer.class, sourceId)).isZero();

            // The earlier test exercises rollback of attachment *metadata*.
            // This second transaction invokes the real local file writer and
            // proves an actual Spring/PostgreSQL rollback removes its file too.
            UUID onDiskSubmission = UUID.randomUUID();
            UUID onDiskAttachment = UUID.randomUUID();
            var attachmentRepository = org.mockito.Mockito.mock(
                    org.odk.platform.repository.SubmissionAttachmentRepository.class);
            var answerRepository = org.mockito.Mockito.mock(
                    org.odk.platform.repository.SubmissionAnswerRepository.class);
            org.mockito.Mockito.when(attachmentRepository.saveAndFlush(
                    org.mockito.Mockito.any(org.odk.platform.domain.SubmissionAttachment.class)))
                    .thenAnswer(invocation -> {
                        var row = (org.odk.platform.domain.SubmissionAttachment)
                                invocation.getArgument(0);
                        jdbcTemplate.update(
                                "INSERT INTO submission_attachments(id,submission_id,file_name,"
                                        + "storage_path) VALUES (?,?,?,?)",
                                onDiskAttachment, onDiskSubmission,
                                row.getFileName(), row.getStoragePath());
                        return row;
                    });
            var attachmentWriter = new org.odk.platform.service.SubmissionAttachmentService(
                    attachmentRepository, answerRepository,
                    org.mockito.Mockito.mock(org.odk.platform.service.SubmissionAttachmentJournal.class));
            org.springframework.test.util.ReflectionTestUtils.setField(
                    attachmentWriter, "submissionsRoot", testFiles.toString());
            var savedPath = new java.util.concurrent.atomic.AtomicReference<java.nio.file.Path>();
            var onDiskCandidate = org.odk.platform.domain.Submission.builder()
                    .id(onDiskSubmission).instanceId("uuid:" + onDiskSubmission).build();
            var onDiskPhoto = new org.springframework.mock.web.MockMultipartFile(
                    "photo", "physical-visit.jpg", "image/jpeg", new byte[]{11, 22, 33});
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.web.server.ResponseStatusException.class,
                    () -> tx.executeWithoutResult(ignored -> {
                        jdbcTemplate.update(
                                "INSERT INTO submissions(id,form_id,form_version_id,instance_id,"
                                        + "xml_content,submitted_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",
                                onDiskSubmission, form, v2,
                                "uuid:" + onDiskSubmission, "<visit>4</visit>");
                        var attachment = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                                () -> attachmentWriter.store(
                                        onDiskCandidate, onDiskPhoto,
                                        "<photo>physical-visit.jpg</photo>"));
                        savedPath.set(java.nio.file.Path.of(attachment.getStoragePath()));
                        assertThat(java.nio.file.Files.isRegularFile(savedPath.get()))
                                .isTrue();
                        work.completeIfPresent(project, actor, null, onDiskCandidate,
                                wrongVersion, failedWorkId.toString());
                    }));
            assertThat(savedPath.get()).isNotNull();
            assertThat(java.nio.file.Files.exists(savedPath.get()))
                    .as("rollback must remove physical attachment, not only its JDBC row")
                    .isFalse();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM submissions WHERE id=?",
                    Integer.class, onDiskSubmission)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM submission_attachments WHERE id=?",
                    Integer.class, onDiskAttachment)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT state FROM record_work_items WHERE id=?",
                    String.class, failedWorkId)).isEqualTo("READY");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM record_links WHERE work_item_id=?",
                    Integer.class, workId)).isEqualTo(1);
        } finally {

            jdbcTemplate.update("DELETE FROM record_links WHERE work_item_id=?", workId);
            jdbcTemplate.update("DELETE FROM record_work_items WHERE id=?", workId);
            jdbcTemplate.update("DELETE FROM record_work_items WHERE id=?", failedWorkId);
            jdbcTemplate.update("DELETE FROM record_work_items WHERE id=?", amendmentWorkId);
            jdbcTemplate.update("DELETE FROM submissions WHERE id IN (?,?)", childId, sourceId);
            jdbcTemplate.update("DELETE FROM form_versions WHERE id IN (?,?)", v1, v2);
            jdbcTemplate.update("DELETE FROM app_users WHERE id=?", appUserId);
            jdbcTemplate.update("DELETE FROM xforms WHERE id=?", form);
            jdbcTemplate.update("DELETE FROM projects WHERE id=?", project);
        }
    }

    @Test
    void authenticatedHttpV1ToV2FollowUpPersistsSeparateRecordOnPostgreSql() throws Exception {
        // HTTP -> Basic principal -> JavaRosa -> the actual PostgreSQL Flyway
        // schema, with no mocked intake, record-work or persistence services.
        UUID owner = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username='admin' LIMIT 1", UUID.class);
        UUID project = UUID.randomUUID();
        UUID form = UUID.randomUUID();
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        UUID appUser = UUID.randomUUID();
        UUID workId = UUID.randomUUID();
        String xmlns = "u06_http_" + project.toString().replace("-", "");
        String sourceInstance = "uuid:" + UUID.randomUUID();
        String visitInstance = "uuid:" + UUID.randomUUID();
        String password = "u06-http-test-password";
        String credential = "Basic " + java.util.Base64.getEncoder().encodeToString(
                ("u06_" + appUser + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String xformV1 = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms">
                  <h:head>
                    <h:title>U06 PostgreSQL visits</h:title>
                    <model>
                      <instance>
                        <data id="%s" version="1">
                          <name/><age/><meta><instanceID/></meta>
                        </data>
                      </instance>
                      <bind nodeset="/data/name" type="string"/>
                      <bind nodeset="/data/age" type="int"/>
                    </model>
                  </h:head>
                  <h:body>
                    <input ref="/data/name"><label>Name</label></input>
                    <input ref="/data/age"><label>Age</label></input>
                  </h:body>
                </h:html>
                """.formatted(xmlns);
        // The newer form renames an integer to a string. There is no
        // authorization to copy the old age value into the new field.
        String xformV2 = xformV1.replace(
                "<data id=\"" + xmlns + "\" version=\"1\">",
                "<data id=\"" + xmlns + "\" version=\"2\">")
                .replace("<age/>", "<ageYears/>")
                .replace("/data/age", "/data/ageYears")
                .replace("nodeset=\"/data/ageYears\" type=\"int\"",
                        "nodeset=\"/data/ageYears\" type=\"string\"");
        assertThat(xformV2).contains("<ageYears/>")
                .contains("nodeset=\"/data/ageYears\" type=\"string\"")
                .isNotEqualTo(xformV1);

        jdbcTemplate.update(
                "INSERT INTO projects(id,name,owner_id,created_by_id) VALUES (?,?,?,?)",
                project, "U06 PostgreSQL HTTP", owner, owner);
        jdbcTemplate.update(
                "INSERT INTO xforms(id,title,xmlns,project_id,created_by_id,is_published) "
                        + "VALUES (?,?,?,?,?,TRUE)",
                form, "U06 PostgreSQL HTTP", xmlns, project, owner);
        jdbcTemplate.update(
                "INSERT INTO form_versions(id,form_id,version,xml_content,xmlns,"
                        + "created_by_id,is_published,is_latest) "
                        + "VALUES (?,?,?,?,?,?,TRUE,FALSE)",
                v1, form, "1", xformV1, xmlns, owner);
        jdbcTemplate.update(
                "INSERT INTO form_versions(id,form_id,version,xml_content,xmlns,"
                        + "created_by_id,is_published,is_latest) "
                        + "VALUES (?,?,?,?,?,?,TRUE,TRUE)",
                v2, form, "2", xformV2, xmlns, owner);
        jdbcTemplate.update(
                "UPDATE xforms SET current_version_id = ? WHERE id = ?", v2, form);
        jdbcTemplate.update(
                "INSERT INTO app_users(id,username,email,password_hash,full_name,"
                        + "is_active,is_verified) VALUES (?,?,?,?,?,TRUE,TRUE)",
                appUser, "u06_" + appUser, appUser + "@example.test",
                passwordEncoder.encode(password), "U06 PG HTTP");
        jdbcTemplate.update(
                "INSERT INTO app_user_projects(id,app_user_id,project_id,form_id,status,"
                        + "assigned_at,created_at,updated_at) "
                        + "VALUES (?,?,?,?,'ACTIVE',CURRENT_TIMESTAMP,"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID(), appUser, project, form);

        String sourceXml = """
                <data id="%s" version="1">
                    <name>First observation</name><age>30</age>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """.formatted(xmlns, sourceInstance);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/v1/projects/{projectId}/submission", project)
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "xml_submission_file", "visit1.xml", "text/xml",
                                sourceXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header("Authorization", credential))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated());
        UUID sourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM submissions WHERE instance_id=?", UUID.class, sourceInstance);
        assertThat(sourceId).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT form_version_id FROM submissions WHERE id=?", UUID.class, sourceId))
                .isEqualTo(v1);

        jdbcTemplate.update(
                "INSERT INTO record_work_items(id,project_id,source_submission_id,"
                        + "source_form_version_id,source_instance_id,target_form_version_id,"
                        + "assigned_app_user_id,work_type,state,client_request_id,visit_key) "
                        + "VALUES (?,?,?,?,?,?,?,'FOLLOW_UP','READY',?,?)",
                workId, project, sourceId, v1, sourceInstance, v2, appUser,
                "u06-http-" + workId, "second-visit");
        // A different, fully authenticated enumerator has the SAME active
        // project/form assignment but not ownership of the source record or
        // work item. Assignment alone must not grant record access or permit
        // a deprecatedID update (even without a work-item header).
        UUID foreignUser = UUID.randomUUID();
        String foreignLogin = "u06_foreign_" + foreignUser;
        String foreignPassword = "foreign-enumerator-test-password";
        String foreignCredential = "Basic " + java.util.Base64.getEncoder()
                .encodeToString((foreignLogin + ":" + foreignPassword)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbcTemplate.update(
                "INSERT INTO app_users(id,username,email,password_hash,full_name,"
                        + "is_active,is_verified) VALUES (?,?,?,?,?,TRUE,TRUE)",
                foreignUser, foreignLogin, foreignUser + "@example.test",
                passwordEncoder.encode(foreignPassword),
                "Other enumerator assigned to same form");
        jdbcTemplate.update(
                "INSERT INTO app_user_projects(id,app_user_id,project_id,form_id,status,"
                        + "assigned_at,created_at,updated_at) "
                        + "VALUES (?,?,?,?,'ACTIVE',CURRENT_TIMESTAMP,"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID(), foreignUser, project, form);

        for (String endpoint : new String[] {
                "/v1/projects/{projectId}/record-work-items/{workId}/prepare",
                "/v1/projects/{projectId}/record-work-items/{workId}/typed-mapping-authority"
        }) {
            var response = mockMvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .get(endpoint, project, workId)
                                    .header("Authorization", foreignCredential))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("First observation")
                    .doesNotContain("<data").doesNotContain("<h:html");
        }
        for (String endpoint : new String[] {
                "/v1/projects/{projectId}/records/{sourceId}/target-versions?workType=FOLLOW_UP",
                "/v1/projects/{projectId}/records/{sourceId}/timeline"
        }) {
            var response = mockMvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .get(endpoint, project, sourceId)
                                    .header("Authorization", foreignCredential))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("First observation")
                    .doesNotContain("thirty-one")
                    .doesNotContain("<data")
                    .doesNotContain("<h:html");
        }

        var foreignRecords = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/v1/projects/{projectId}/records", project)
                                .header("Authorization", foreignCredential))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andReturn().getResponse().getContentAsString();
        var foreignWork = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/v1/projects/{projectId}/record-work-items", project)
                                .header("Authorization", foreignCredential))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andReturn().getResponse().getContentAsString();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(json.readTree(foreignRecords).size()).isZero();
        assertThat(json.readTree(foreignWork).size()).isZero();

        // Attack 1: no work-item header at all. The legacy deprecatedID route
        // previously looked up the original but failed to check source owner.
        // A forged same-form amendment must fail before any submission write.
        String forgedReplacementId = "uuid:" + UUID.randomUUID();
        String forgedReplacementXml = """
                <data id="%s" version="2">
                    <name>Cross-enumerator forged replacement</name>
                    <ageYears>untrusted</ageYears>
                    <meta><instanceID>%s</instanceID><deprecatedID>%s</deprecatedID></meta>
                </data>
                """.formatted(xmlns, forgedReplacementId, sourceInstance);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/v1/projects/{projectId}/submission", project)
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "xml_submission_file", "foreign-replacement.xml", "text/xml",
                                forgedReplacementXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header("Authorization", foreignCredential))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isNotFound());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submissions WHERE instance_id=?", Integer.class,
                forgedReplacementId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_versions WHERE submission_id=?", Integer.class,
                sourceId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT xml_content FROM submissions WHERE id=?", String.class, sourceId))
                .isEqualTo(sourceXml);

        // Attack 2: a new valid instance with the other enumerator's work-id
        // reaches the transactional work-completion check. It must fail and
        // roll back the new submission and its physical upload.
        String stolenWorkInstance = "uuid:" + UUID.randomUUID();
        String stolenFileName = "foreign-" + UUID.randomUUID() + ".jpg";
        String stolenXml = """
                <data id="%s" version="2">
                    <name>Foreign work completion</name><ageYears>not allowed</ageYears>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """.formatted(xmlns, stolenWorkInstance);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/v1/projects/{projectId}/submission", project)
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "xml_submission_file", "foreign-visit.xml", "text/xml",
                                stolenXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "photo", stolenFileName, "image/jpeg",
                                new byte[]{13, 12, 11, 10}))
                        .header("Authorization", foreignCredential)
                        .header("X-DataCollector-Work-Item-Id", workId.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isNotFound());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submissions WHERE instance_id=?", Integer.class,
                stolenWorkInstance)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_attachments WHERE file_name=?", Integer.class,
                stolenFileName)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM record_links WHERE work_item_id=?", Integer.class,
                workId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM record_work_items WHERE id=?", String.class, workId))
                .isEqualTo("READY");
        java.nio.file.Path foreignRoot = java.nio.file.Path.of(submissionsRoot)
                .toAbsolutePath().normalize();
        if (java.nio.file.Files.isDirectory(foreignRoot)) {
            try (var localFiles = java.nio.file.Files.walk(foreignRoot)) {
                assertThat(localFiles.filter(java.nio.file.Files::isRegularFile)
                        .filter(path -> path.getFileName().toString()
                                .endsWith("_" + stolenFileName))
                        .count()).isZero();
            }
        }

        // Production PostgreSQL + real Basic auth: preparation must show
        // exact source and target schemas and a review-only bind diff.
        var previewResult = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/v1/projects/{projectId}/record-work-items/{workId}/prepare",
                                        project, workId)
                                .header("Authorization", credential))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andReturn();
        var preview = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                previewResult.getResponse().getContentAsString());
        assertThat(preview.path("sourceXml").asText()).isEqualTo(sourceXml);
        assertThat(preview.path("sourceXFormXml").asText()).isEqualTo(xformV1);
        assertThat(preview.path("targetXFormXml").asText()).isEqualTo(xformV2);
        assertThat(preview.path("mappingApprovalRequired").asBoolean()).isTrue();
        assertThat(preview.path("compatibility").path("status").asText())
                .isEqualTo("BIND_DELTA_REVIEW_REQUIRED");
        assertThat(preview.path("compatibility").path("mappingApprovalRequired").asBoolean())
                .isTrue();
        var changes = preview.path("compatibility").path("changes");
        assertThat(changes.findValuesAsText("kind")).contains("REMOVED", "ADDED");
        assertThat(changes.findValuesAsText("path")).contains(
                "/data/age", "/data/ageYears");
        assertThat(preview.path("submissionSemantics").asText())
                .contains("WITHOUT deprecatedID");

        // Typed mapping authority never issues an implicit read/transfer
        // grant. It must not leak raw answers/XForms even though prepare()
        // may expose them to the authorized source owner.
        var authorityResponse = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/v1/projects/{projectId}/record-work-items/{workId}/typed-mapping-authority",
                                        project, workId)
                                .header("Authorization", credential))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andReturn().getResponse().getContentAsString();
        var authority = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                authorityResponse);
        assertThat(authority.path("state").asText())
                .isEqualTo("DENIED_INDEPENDENT_VISIT");
        assertThat(authority.path("canTransfer").asBoolean()).isFalse();
        assertThat(authority.path("canReadHistoricalAnswers").asBoolean()).isFalse();
        assertThat(authority.path("canSubmit").asBoolean()).isFalse();
        assertThat(authority.path("approvedPaths").size()).isZero();
        assertThat(authorityResponse).doesNotContain("First observation")
                .doesNotContain("<data").doesNotContain("<h:html");

        // Revoked assignment must deny BOTH preparation and mapping
        // authority, even when the exact READY work item still exists.
        jdbcTemplate.update(
                "UPDATE app_user_projects SET status = 'INACTIVE' "
                        + "WHERE app_user_id = ? AND project_id = ? AND form_id = ?",
                appUser, project, form);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/v1/projects/{projectId}/record-work-items/{workId}/prepare",
                                project, workId)
                        .header("Authorization", credential))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/v1/projects/{projectId}/record-work-items/{workId}/typed-mapping-authority",
                                project, workId)
                        .header("Authorization", credential))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isForbidden());
        jdbcTemplate.update(
                "UPDATE app_user_projects SET status = 'ACTIVE' "
                        + "WHERE app_user_id = ? AND project_id = ? AND form_id = ?",
                appUser, project, form);

        // Invalid client semantics: a FOLLOW_UP work item cannot be used
        // with deprecatedID to amend its V1 source. This rejection happens
        // AFTER the intake pipeline has written submission/answer/media.
        // The real Spring rollback must remove both JDBC rows and physical file.
        String invalidInstance = "uuid:" + UUID.randomUUID();
        String failedPhotoName = "rejected-" + UUID.randomUUID() + ".jpg";
        String invalidAmendmentXml = """
                <data id="%s" version="2">
                    <name>Forbidden follow-up amendment</name>
                    <ageYears>must not persist</ageYears>
                    <meta><instanceID>%s</instanceID>
                          <deprecatedID>%s</deprecatedID></meta>
                </data>
                """.formatted(xmlns, invalidInstance, sourceInstance);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/v1/projects/{projectId}/submission", project)
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "xml_submission_file", "invalid-amendment.xml", "text/xml",
                                invalidAmendmentXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "photo", failedPhotoName, "image/jpeg",
                                new byte[]{8, 7, 6, 5, 4, 3, 2}))
                        .header("Authorization", credential)
                        .header("X-DataCollector-Work-Item-Id", workId.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submissions WHERE instance_id=?", Integer.class, invalidInstance))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_versions WHERE submission_id=?", Integer.class, sourceId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_attachments WHERE file_name=?", Integer.class,
                failedPhotoName)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM record_work_items WHERE id=?", String.class, workId))
                .isEqualTo("READY");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM record_links WHERE work_item_id=?", Integer.class, workId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT xml_content FROM submissions WHERE id=?", String.class, sourceId))
                .isEqualTo(sourceXml);

        java.nio.file.Path localRoot = java.nio.file.Path.of(submissionsRoot)
                .toAbsolutePath().normalize();
        if (java.nio.file.Files.isDirectory(localRoot)) {
            try (var files = java.nio.file.Files.walk(localRoot)) {
                assertThat(files.filter(java.nio.file.Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith("_" + failedPhotoName))
                        .count())
                        .as("Rejected amendment must not orphan the physical uploaded media")
                        .isZero();
            }
        }

        String followUpXml = """
                <data id="%s" version="2">
                    <name>Second observation</name><ageYears>thirty-one</ageYears>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """.formatted(xmlns, visitInstance);
        var followUp = new org.springframework.mock.web.MockMultipartFile(
                "xml_submission_file", "visit2.xml", "text/xml",
                followUpXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/v1/projects/{projectId}/submission", project)
                        .file(followUp)
                        .header("Authorization", credential)
                        .header("X-DataCollector-Work-Item-Id", workId.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated());

        UUID visitId = jdbcTemplate.queryForObject(
                "SELECT id FROM submissions WHERE instance_id=?", UUID.class, visitInstance);
        assertThat(visitId).isNotNull().isNotEqualTo(sourceId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT form_version_id FROM submissions WHERE id=?", UUID.class, visitId))
                .isEqualTo(v2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT value_number FROM submission_answers "
                        + "WHERE submission_id=? AND path='age'",
                java.math.BigDecimal.class, sourceId))
                .isEqualByComparingTo("30");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT value_text FROM submission_answers "
                        + "WHERE submission_id=? AND path='ageYears'",
                String.class, visitId))
                .isEqualTo("thirty-one");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_answers "
                        + "WHERE submission_id=? AND path='age'",
                Integer.class, visitId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT xml_content FROM submissions WHERE id=?", String.class, sourceId))
                .isEqualTo(sourceXml);
        var link = jdbcTemplate.queryForMap(
                "SELECT source_submission_id,child_submission_id,link_type,visit_key "
                        + "FROM record_links WHERE work_item_id=?", workId);
        assertThat(link.get("source_submission_id")).isEqualTo(sourceId);
        assertThat(link.get("child_submission_id")).isEqualTo(visitId);
        assertThat(link.get("link_type")).isEqualTo("FOLLOW_UP");
        assertThat(link.get("visit_key")).isEqualTo("second-visit");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM record_work_items WHERE id=?", String.class, workId))
                .isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_versions WHERE submission_id=?",
                Integer.class, sourceId)).isZero();

        // Idempotent submission replay cannot create another child or link.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/v1/projects/{projectId}/submission", project)
                        .file(followUp)
                        .header("Authorization", credential)
                        .header("X-DataCollector-Work-Item-Id", workId.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM record_links WHERE work_item_id=?",
                Integer.class, workId)).isEqualTo(1);

        // Completed work must no longer expose source XML in preparation or
        // a typed-mapping response, even to its former authorized owner.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/v1/projects/{projectId}/record-work-items/{workId}/prepare",
                                project, workId)
                        .header("Authorization", credential))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isConflict());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/v1/projects/{projectId}/record-work-items/{workId}/typed-mapping-authority",
                                project, workId)
                        .header("Authorization", credential))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isConflict());
    }

    @Test
    void authenticatedHttpV1ToV2AmendmentPreservesIndexedRepeatsOnPostgreSql()
            throws Exception {
        // Real PostgreSQL 15/Flyway, MockMvc Basic authentication, JavaRosa
        // and normalized answer persistence; no mocked intake repositories.
        UUID owner = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username='admin' LIMIT 1", UUID.class);
        UUID project = UUID.randomUUID();
        UUID form = UUID.randomUUID();
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        UUID appUser = UUID.randomUUID();
        String formName = "u06_pg_repeat_" + project.toString().replace("-", "");
        String sourceInstance = "uuid:" + UUID.randomUUID();
        String revisedInstance = "uuid:" + UUID.randomUUID();
        String login = "u06_pg_repeat_" + appUser;
        String password = "test-repeat-amendment-only";
        String credential = "Basic " + java.util.Base64.getEncoder().encodeToString(
                (login + ":" + password)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String xformV1 = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms">
                  <h:head><h:title>U06 PostgreSQL repeat amendment</h:title>
                    <model>
                      <instance>
                        <data id="%s" version="1.0">
                          <name/><photo/>
                          <children><child_name/><child_age/></children>
                          <meta><instanceID/></meta>
                        </data>
                      </instance>
                      <bind nodeset="/data/name" type="string"/>
                      <bind nodeset="/data/photo" type="binary"/>
                      <bind nodeset="/data/children/child_name" type="string"/>
                      <bind nodeset="/data/children/child_age" type="int"/>
                    </model>
                  </h:head>
                  <h:body>
                    <input ref="/data/name"><label>Respondent</label></input>
                    <upload ref="/data/photo" mediatype="image/*"><label>Photo</label></upload>
                    <repeat nodeset="/data/children">
                      <label>Children</label>
                      <input ref="/data/children/child_name"><label>Child name</label></input>
                      <input ref="/data/children/child_age"><label>Age</label></input>
                    </repeat>
                  </h:body>
                </h:html>
                """.formatted(formName);
        String xformV2 = xformV1
                .replace("<data id=\"" + formName + "\" version=\"1.0\">",
                        "<data id=\"" + formName + "\" version=\"2.0\">")
                .replace("<child_age/>", "<child_age_text/>")
                .replace("/data/children/child_age", "/data/children/child_age_text")
                .replace("nodeset=\"/data/children/child_age_text\" type=\"int\"",
                        "nodeset=\"/data/children/child_age_text\" type=\"string\"");
        assertThat(xformV2).contains("version=\"2.0\"")
                .contains("nodeset=\"/data/children/child_age_text\" type=\"string\"")
                .doesNotContain("nodeset=\"/data/children/child_age\" type=\"int\"");

        jdbcTemplate.update(
                "INSERT INTO projects(id,name,owner_id,created_by_id) VALUES (?,?,?,?)",
                project, "U06 PG Repeat", owner, owner);
        jdbcTemplate.update(
                "INSERT INTO xforms(id,title,xmlns,project_id,created_by_id,is_published) "
                        + "VALUES (?,?,?,?,?,TRUE)",
                form, "U06 PG Repeat", formName, project, owner);
        jdbcTemplate.update(
                "INSERT INTO form_versions(id,form_id,version,xml_content,xmlns,"
                        + "created_by_id,is_published,is_latest) "
                        + "VALUES (?,?,?,?,?,?,TRUE,FALSE)",
                v1, form, "1.0", xformV1, formName, owner);
        jdbcTemplate.update(
                "INSERT INTO form_versions(id,form_id,version,xml_content,xmlns,"
                        + "created_by_id,is_published,is_latest) "
                        + "VALUES (?,?,?,?,?,?,TRUE,TRUE)",
                v2, form, "2.0", xformV2, formName, owner);
        jdbcTemplate.update("UPDATE xforms SET current_version_id=? WHERE id=?", v2, form);
        jdbcTemplate.update(
                "INSERT INTO app_users(id,username,email,password_hash,full_name,"
                        + "is_active,is_verified) VALUES (?,?,?,?,?,TRUE,TRUE)",
                appUser, login, appUser + "@example.test",
                passwordEncoder.encode(password), "U06 PostgreSQL repeat owner");
        jdbcTemplate.update(
                "INSERT INTO app_user_projects(id,app_user_id,project_id,form_id,status,"
                        + "assigned_at,created_at,updated_at) "
                        + "VALUES (?,?,?,?,'ACTIVE',CURRENT_TIMESTAMP,"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID(), appUser, project, form);

        // Late V1 intake after V2 publication must select exactly the V1
        // definition and retain both integer-valued child repeats.
        String originalXml = """
                <data id="%s" version="1.0">
                  <name>Original household</name>
                  <photo>household-v1.jpg</photo>
                  <children><child_name>Amina</child_name><child_age>5</child_age></children>
                  <children><child_name>Juma</child_name><child_age>3</child_age></children>
                  <meta><instanceID>%s</instanceID></meta>
                </data>
                """.formatted(formName, sourceInstance);
        byte[] sourcePhoto = new byte[]{1, 3, 5, 7, 9, 11};
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/v1/projects/{projectId}/submission", project)
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "xml_submission_file", "repeat-v1.xml", "text/xml",
                                originalXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "photo", "household-v1.jpg", "image/jpeg", sourcePhoto))
                        .header("Authorization", credential))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated());
        UUID sourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM submissions WHERE instance_id=?", UUID.class, sourceInstance);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT form_version_id FROM submissions WHERE id=?", UUID.class, sourceId))
                .isEqualTo(v1);
        // PostgreSQL metadata must point to a real source-specific file,
        // linked to the exact binary answer of the original V1 submission.
        var sourceAttachment = jdbcTemplate.queryForMap(
                "SELECT sa.file_name,sa.storage_path,sa.content_hash,sa.answer_id,"
                        + "a.path AS answer_path FROM submission_attachments sa "
                        + "LEFT JOIN submission_answers a ON a.id=sa.answer_id "
                        + "WHERE sa.submission_id=?", sourceId);
        assertThat(sourceAttachment.get("file_name")).isEqualTo("household-v1.jpg");
        assertThat(sourceAttachment.get("answer_id")).isNotNull();
        assertThat(sourceAttachment.get("answer_path")).isEqualTo("photo");
        String oldPhotoPath = sourceAttachment.get("storage_path").toString();
        assertThat(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(oldPhotoPath)))
                .containsExactly(sourcePhoto);
        String oldPhotoHash = sourceAttachment.get("content_hash").toString();

        var v1Rows = jdbcTemplate.queryForList(
                "SELECT path,value_number,repeat_path,repeat_index FROM submission_answers "
                        + "WHERE submission_id=? AND path LIKE 'children%/child_age' ORDER BY path",
                sourceId);
        assertThat(v1Rows).hasSize(2);
        assertThat(v1Rows.get(0).get("path")).isEqualTo("children[0]/child_age");
        assertThat(v1Rows.get(1).get("path")).isEqualTo("children[1]/child_age");
        assertThat(v1Rows.get(0).get("repeat_path")).isEqualTo("children");
        assertThat(((Number) v1Rows.get(0).get("repeat_index")).intValue()).isZero();
        assertThat(((Number) v1Rows.get(1).get("repeat_index")).intValue()).isEqualTo(1);
        assertThat(new java.math.BigDecimal(v1Rows.get(0).get("value_number").toString()))
                .isEqualByComparingTo("5");
        assertThat(new java.math.BigDecimal(v1Rows.get(1).get("value_number").toString()))
                .isEqualByComparingTo("3");

        // Offline device A opens V1 for a V2 amendment. Another session B
        // successfully corrects the source before device A reconnects.
        // The server-owned work creation timestamp becomes the revision
        // watermark; neither Android's clock nor an echoed client token
        // is trusted for this comparison.
        UUID staleWorkId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO record_work_items(id,project_id,source_submission_id,"
                        + "source_form_version_id,source_instance_id,target_form_version_id,"
                        + "assigned_app_user_id,work_type,state,client_request_id) "
                        + "VALUES (?,?,?,?,?,?,?,'AMENDMENT','READY',?)",
                staleWorkId, project, sourceId, v1, sourceInstance,
                v2, appUser, "offline-pg-" + staleWorkId);

        String amendedXml = """
                <data id="%s" version="2.0">
                  <name>Corrected household</name>
                  <photo>household-v2.jpg</photo>
                  <children><child_name>Amina</child_name>
                    <child_age_text>five years</child_age_text></children>
                  <children><child_name>Juma</child_name>
                    <child_age_text>three years</child_age_text></children>
                  <meta><instanceID>%s</instanceID><deprecatedID>%s</deprecatedID></meta>
                </data>
                """.formatted(formName, revisedInstance, sourceInstance);
        byte[] revisedPhoto = new byte[]{2, 4, 6, 8, 10, 12, 14};
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/v1/projects/{projectId}/submission", project)
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "xml_submission_file", "repeat-v2.xml", "text/xml",
                                amendedXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "photo", "household-v2.jpg", "image/jpeg", revisedPhoto))
                        .header("Authorization", credential))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated());

        UUID amendedId = jdbcTemplate.queryForObject(
                "SELECT id FROM submissions WHERE instance_id=?", UUID.class, revisedInstance);
        assertThat(amendedId).isNotEqualTo(sourceId);
        var amendedAttachment = jdbcTemplate.queryForMap(
                "SELECT sa.file_name,sa.storage_path,sa.content_hash,sa.answer_id,"
                        + "a.path AS answer_path FROM submission_attachments sa "
                        + "LEFT JOIN submission_answers a ON a.id=sa.answer_id "
                        + "WHERE sa.submission_id=?", amendedId);
        assertThat(amendedAttachment.get("file_name")).isEqualTo("household-v2.jpg");
        assertThat(amendedAttachment.get("answer_id")).isNotNull();
        assertThat(amendedAttachment.get("answer_path")).isEqualTo("photo");
        String amendedPhotoPath = amendedAttachment.get("storage_path").toString();
        assertThat(amendedPhotoPath).isNotEqualTo(oldPhotoPath);
        assertThat(amendedAttachment.get("content_hash"))
                .isNotEqualTo(oldPhotoHash);
        assertThat(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(amendedPhotoPath)))
                .containsExactly(revisedPhoto);
        // V1 media remains byte-identical and in the V1 submission directory
        // after V2 creates its own attachment and new child repeat answers.
        assertThat(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(oldPhotoPath)))
                .containsExactly(sourcePhoto);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_attachments WHERE submission_id=?",
                Integer.class, sourceId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_attachments WHERE submission_id=?",
                Integer.class, amendedId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT form_version_id FROM submissions WHERE id=?", UUID.class, sourceId))
                .isEqualTo(v1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT xml_content FROM submissions WHERE id=?", String.class, sourceId))
                .isEqualTo(originalXml);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT form_version_id FROM submissions WHERE id=?", UUID.class, amendedId))
                .isEqualTo(v2);
        var archive = jdbcTemplate.queryForMap(
                "SELECT xml_content,source_form_version_id,instance_id "
                        + "FROM submission_versions WHERE submission_id=?", sourceId);
        assertThat(archive.get("xml_content")).isEqualTo(originalXml);
        assertThat(archive.get("source_form_version_id")).isEqualTo(v1);
        assertThat(archive.get("instance_id")).isEqualTo(sourceInstance);

        var intactV1 = jdbcTemplate.queryForList(
                "SELECT path,value_number,repeat_index FROM submission_answers "
                        + "WHERE submission_id=? AND path LIKE 'children%/child_age' ORDER BY path",
                sourceId);
        assertThat(intactV1).hasSize(2);
        assertThat(new java.math.BigDecimal(intactV1.get(0).get("value_number").toString()))
                .isEqualByComparingTo("5");
        assertThat(new java.math.BigDecimal(intactV1.get(1).get("value_number").toString()))
                .isEqualByComparingTo("3");
        var newRows = jdbcTemplate.queryForList(
                "SELECT path,value_text,repeat_path,repeat_index FROM submission_answers "
                        + "WHERE submission_id=? AND path LIKE 'children%/child_age_text' "
                        + "ORDER BY path", amendedId);
        assertThat(newRows).hasSize(2);
        assertThat(newRows.get(0).get("path")).isEqualTo("children[0]/child_age_text");
        assertThat(newRows.get(1).get("path")).isEqualTo("children[1]/child_age_text");
        assertThat(newRows.get(0).get("value_text")).isEqualTo("five years");
        assertThat(newRows.get(1).get("value_text")).isEqualTo("three years");
        assertThat(((Number) newRows.get(0).get("repeat_index")).intValue()).isZero();
        assertThat(((Number) newRows.get(1).get("repeat_index")).intValue()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_answers "
                        + "WHERE submission_id=? AND path LIKE 'children%/child_age'",
                Integer.class, amendedId)).isZero();

        // Stale prepare must report a conflict before exposing the outdated
        // V1 answers as a draft for another AMENDMENT attempt.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/v1/projects/{projectId}/record-work-items/{workId}/prepare",
                                project, staleWorkId)
                        .header("Authorization", credential))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isConflict());

        String staleInstance = "uuid:" + UUID.randomUUID();
        String staleFileName = "offline-stale-" + UUID.randomUUID() + ".jpg";
        String staleXml = """
                <data id="%s" version="2.0">
                  <name>Offline stale correction</name>
                  <photo>%s</photo>
                  <children><child_name>Amina</child_name>
                    <child_age_text>outdated</child_age_text></children>
                  <meta><instanceID>%s</instanceID><deprecatedID>%s</deprecatedID></meta>
                </data>
                """.formatted(formName, staleFileName, staleInstance, sourceInstance);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/v1/projects/{projectId}/submission", project)
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "xml_submission_file", "offline-stale.xml", "text/xml",
                                staleXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "photo", staleFileName, "image/jpeg",
                                new byte[]{42, 41, 40}))
                        .header("Authorization", credential)
                        .header("X-DataCollector-Work-Item-Id", staleWorkId.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submissions WHERE instance_id=?", Integer.class,
                staleInstance)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_attachments WHERE file_name=?", Integer.class,
                staleFileName)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_versions WHERE submission_id=?", Integer.class,
                sourceId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM record_work_items WHERE id=?", String.class,
                staleWorkId)).isEqualTo("READY");
        assertThat(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(oldPhotoPath)))
                .containsExactly(sourcePhoto);
        assertThat(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(amendedPhotoPath)))
                .containsExactly(revisedPhoto);

        // An old V1 submission remains visible in the audit/listing API,
        // but must be marked as superseded once its replacement is archived.
        // Recovery UI may display history; it cannot assume the old source is
        // eligible to start a NEW amendment just because it is still listed.
        var recordsJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/v1/projects/{projectId}/records", project)
                                .header("Authorization", credential))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                .status().isOk())
                        .andReturn().getResponse().getContentAsString());
        assertThat(recordsJson.isArray()).isTrue();
        assertThat(recordsJson.size()).isEqualTo(2);
        java.util.Map<String, Boolean> superseded = new java.util.HashMap<>();
        recordsJson.forEach(node -> {
            assertThat(node.has("amendmentSuperseded")).isTrue();
            superseded.put(node.get("submissionId").asText(),
                    node.get("amendmentSuperseded").asBoolean());
        });
        assertThat(superseded.get(sourceId.toString())).isTrue();
        assertThat(superseded.get(amendedId.toString())).isFalse();

        // Real TCP loopback exercises embedded servlet listener, Basic filter,
        // and exact JSON responses on PostgreSQL/Flyway, not MockMvc.
        var socketClient = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        String socketBase = "http://127.0.0.1:" + loopbackPort
                + "/v1/projects/" + project;
        var wireRecords = socketClient.send(
                java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create(socketBase + "/records"))
                        .header("Authorization", credential)
                        .header("Accept", "application/json")
                        .timeout(java.time.Duration.ofSeconds(15)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(wireRecords.statusCode()).isEqualTo(200);
        var realRecords = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(wireRecords.body());
        assertThat(realRecords.size()).isEqualTo(2);
        java.util.Map<String, Boolean> realStatus = new java.util.HashMap<>();
        realRecords.forEach(node -> realStatus.put(node.path("submissionId").asText(),
                node.path("amendmentSuperseded").asBoolean(false)));
        assertThat(realStatus.get(sourceId.toString())).isTrue();
        assertThat(realStatus.get(amendedId.toString())).isFalse();

        var unauthorized = socketClient.send(
                java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create(socketBase + "/records"))
                        .timeout(java.time.Duration.ofSeconds(15)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat(unauthorized.body()).doesNotContain("Original household")
                .doesNotContain("Corrected household").doesNotContain(sourceInstance);

        // New AMENDMENT against the *old* V1 source is denied even if a
        // previous offline task has already become stale. The rejection
        // occurs in work-item creation BEFORE exposing another draft.
        String oldRequestId = "old-source-" + UUID.randomUUID();
        String oldAmendmentRequest = """
                {"sourceSubmissionId":"%s","targetFormVersionId":"%s",
                 "workType":"AMENDMENT","clientRequestId":"%s"}
                """.formatted(sourceId, v2, oldRequestId);
        var staleWire = socketClient.send(
                java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create(socketBase + "/record-work-items"))
                        .header("Authorization", credential)
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofSeconds(15))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                oldAmendmentRequest)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(staleWire.statusCode()).isEqualTo(409);
        assertThat(staleWire.body()).doesNotContain("Original household")
                .doesNotContain(sourceInstance);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM record_work_items WHERE client_request_id=?",
                Integer.class, oldRequestId)).isZero();

        // FOLLOW_UP is an independent second visit, not an amendment. It is
        // still allowed against V1 if the enumerator explicitly chooses that
        // historic visit, and it does not change the V1 archive.
        String followUpRequestId = "historic-visit-" + UUID.randomUUID();
        String followUpRequest = """
                {"sourceSubmissionId":"%s","targetFormVersionId":"%s",
                 "workType":"FOLLOW_UP","clientRequestId":"%s",
                 "visitKey":"visit-from-historic-source"}
                """.formatted(sourceId, v2, followUpRequestId);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/v1/projects/{projectId}/record-work-items", project)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(followUpRequest)
                        .header("Authorization", credential))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_versions WHERE submission_id=?",
                Integer.class, sourceId)).isEqualTo(1);

        // Fresh work created against the most recently submitted revision
        // MUST still work: the stale guard cannot prohibit legitimate later
        // corrections that reference the newest instance instead of V1.
        String freshRequestId = "fresh-source-" + UUID.randomUUID();
        String freshRequest = """
                {"sourceSubmissionId":"%s","targetFormVersionId":"%s",
                 "workType":"AMENDMENT","clientRequestId":"%s"}
                """.formatted(amendedId, v2, freshRequestId);
        var freshWire = socketClient.send(
                java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create(socketBase + "/record-work-items"))
                        .header("Authorization", credential)
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofSeconds(15))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                freshRequest)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(freshWire.statusCode()).isEqualTo(201);
        String freshWorkResponse = freshWire.body();
        UUID freshWorkId = UUID.fromString(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(freshWorkResponse).get("id").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT source_submission_id FROM record_work_items WHERE id=?",
                UUID.class, freshWorkId)).isEqualTo(amendedId);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/v1/projects/{projectId}/record-work-items/{workId}/prepare",
                                project, freshWorkId)
                        .header("Authorization", credential))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());
        String freshInstance = "uuid:" + UUID.randomUUID();
        String freshXml = """
                <data id="%s" version="2.0">
                  <name>Fresh correction on latest</name>
                  <children><child_name>Amina</child_name>
                    <child_age_text>six years</child_age_text></children>
                  <meta><instanceID>%s</instanceID><deprecatedID>%s</deprecatedID></meta>
                </data>
                """.formatted(formName, freshInstance, revisedInstance);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/v1/projects/{projectId}/submission", project)
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "xml_submission_file", "fresh-correction.xml", "text/xml",
                                freshXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header("Authorization", credential)
                        .header("X-DataCollector-Work-Item-Id", freshWorkId.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated());
        UUID latestId = jdbcTemplate.queryForObject(
                "SELECT id FROM submissions WHERE instance_id=?", UUID.class, freshInstance);
        assertThat(latestId).isNotEqualTo(sourceId).isNotEqualTo(amendedId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM record_work_items WHERE id=?", String.class,
                freshWorkId)).isEqualTo("COMPLETED");
        var freshLink = jdbcTemplate.queryForMap(
                "SELECT source_submission_id,child_submission_id,link_type "
                        + "FROM record_links WHERE work_item_id=?", freshWorkId);
        assertThat(freshLink.get("source_submission_id")).isEqualTo(amendedId);
        assertThat(freshLink.get("child_submission_id")).isEqualTo(latestId);
        assertThat(freshLink.get("link_type")).isEqualTo("AMENDMENT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_versions WHERE submission_id=?", Integer.class,
                amendedId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_versions WHERE submission_id=?", Integer.class,
                sourceId)).isEqualTo(1);

        // Repeat submission with a stale instance ID is never a second edit.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/v1/projects/{projectId}/submission", project)
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "xml_submission_file", "repeat-v2-replay.xml", "text/xml",
                                amendedXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header("Authorization", credential))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_versions WHERE submission_id=?",
                Integer.class, sourceId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submissions WHERE instance_id=?", Integer.class,
                revisedInstance)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_attachments WHERE submission_id=?",
                Integer.class, amendedId)).isEqualTo(1);
        assertThat(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(oldPhotoPath)))
                .containsExactly(sourcePhoto);
        assertThat(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(amendedPhotoPath)))
                .containsExactly(revisedPhoto);
    }

}
