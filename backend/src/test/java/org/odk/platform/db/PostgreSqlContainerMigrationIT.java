package org.odk.platform.db;

import org.junit.jupiter.api.Test;
import org.odk.platform.repository.SubmissionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
@SpringBootTest(
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

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    SubmissionRepository submissionRepository;

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
}
