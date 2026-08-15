package org.odk.platform.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Locale;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the test database schema and seed data are initialized correctly.
 *
 * This is intentionally a JDBC slice test rather than a full @SpringBootTest.
 * Migration/schema verification only requires the DataSource/JdbcTemplate layer;
 * loading the complete web, security, service, and controller application context
 * makes this test fail when an unrelated application bean cannot start.
 */
@JdbcTest
@ActiveProfiles("test")
@ContextConfiguration(classes = MigrationTest.JdbcSliceConfiguration.class)
class MigrationTest {

    @Configuration(proxyBeanMethods = false)
    static class JdbcSliceConfiguration {
        // Intentionally empty. Using a plain @Configuration makes this the
        // slice's configuration root, so OdkPlatformApplication (and its
        // @EnableJpaAuditing) is not added to this JDBC-only test context.
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void schemaIsCreatedSuccessfully() {
        // Verify key tables exist. H2 normally exposes unquoted identifiers in
        // uppercase, but normalize the result so the assertion is case-insensitive.
        var tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'",
                String.class
        );

        var upperTables = tables.stream()
                .map(name -> name.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());

        assertThat(upperTables).contains(
                "USERS",
                "PROJECTS",
                "ROLES",
                "PERMISSIONS",
                "XFORMS",
                "SUBMISSIONS",
                "APP_USERS",
                "REFRESH_TOKENS",
                "AUDIT_LOGS",
                "PROJECT_MEMBERS",
                "APP_USER_PROJECTS",
                "PROJECT_SETTINGS"
        );

        // Verify default system roles were seeded.
        Long roleCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roles WHERE is_system = true",
                Long.class
        );
        assertThat(roleCount).isNotNull().isGreaterThanOrEqualTo(6L);

        // Verify default permissions were seeded.
        Long permissionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM permissions",
                Long.class
        );
        assertThat(permissionCount).isNotNull().isGreaterThanOrEqualTo(20L);

        // Verify project_settings is queryable after schema initialization.
        Long projectSettingsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project_settings",
                Long.class
        );
        assertThat(projectSettingsCount).isNotNull().isGreaterThanOrEqualTo(0L);

        // Verify refresh_tokens is queryable and remains part of the schema contract.
        Long refreshTokenCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens",
                Long.class
        );
        assertThat(refreshTokenCount).isNotNull().isGreaterThanOrEqualTo(0L);
    }
}
