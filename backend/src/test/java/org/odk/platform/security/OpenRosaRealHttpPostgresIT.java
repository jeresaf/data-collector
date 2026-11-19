package org.odk.platform.security;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reuses the complete synthetic M2 loopback HTTP scenario against PostgreSQL 15,
 * including the production Flyway schema and normalized submission persistence.
 * The container and credentials exist only for this disposable CI execution.
 */
@Testcontainers
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
        "app.storage.local.path=${java.io.tmpdir}/odk-m2-http-postgres-it"
    }
)
@ActiveProfiles(value = "postgres-it", inheritProfiles = false)
class OpenRosaRealHttpPostgresIT extends OpenRosaRealHttpIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("m2_openrosa_smoke")
            .withUsername("m2_smoke")
            .withPassword("ephemeral-only-smoke-password");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
}
