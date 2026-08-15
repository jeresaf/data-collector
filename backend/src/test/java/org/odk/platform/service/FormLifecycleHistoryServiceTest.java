package org.odk.platform.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FormLifecycleHistoryServiceTest {

    private EmbeddedDatabase database;
    private JdbcTemplate jdbcTemplate;
    private FormLifecycleHistoryService service;

    private UUID projectId;
    private UUID formId;
    private UUID version1Id;
    private UUID version2Id;
    private UUID userId;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .build();
        jdbcTemplate = new JdbcTemplate(database);
        service = new FormLifecycleHistoryService(jdbcTemplate);
        createSchema();
        seedData();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.shutdown();
        }
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE users (
                    id UUID PRIMARY KEY,
                    username VARCHAR(255) NOT NULL,
                    first_name VARCHAR(100),
                    last_name VARCHAR(100)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE xforms (
                    id UUID PRIMARY KEY,
                    project_id UUID NOT NULL,
                    title VARCHAR(255)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE form_versions (
                    id UUID PRIMARY KEY,
                    form_id UUID NOT NULL,
                    version VARCHAR(50) NOT NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
                    is_published BOOLEAN NOT NULL DEFAULT FALSE,
                    is_latest BOOLEAN NOT NULL DEFAULT FALSE,
                    changelog VARCHAR(1000),
                    published_at TIMESTAMP WITH TIME ZONE,
                    validated_at TIMESTAMP WITH TIME ZONE,
                    deprecated_at TIMESTAMP WITH TIME ZONE,
                    archived_at TIMESTAMP WITH TIME ZONE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE form_publish_history (
                    id UUID PRIMARY KEY,
                    form_version_id UUID NOT NULL,
                    action VARCHAR(50) NOT NULL,
                    previous_version_id UUID,
                    performed_by UUID NOT NULL,
                    performed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    notes VARCHAR(1000)
                )
                """);
    }

    private void seedData() {
        projectId = UUID.randomUUID();
        formId = UUID.randomUUID();
        version1Id = UUID.randomUUID();
        version2Id = UUID.randomUUID();
        userId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, username, first_name, last_name) VALUES (?, ?, ?, ?)",
                userId, "admin", "System", "Administrator");
        jdbcTemplate.update(
                "INSERT INTO xforms (id, project_id, title) VALUES (?, ?, ?)",
                formId, projectId, "Household Survey");
        jdbcTemplate.update(
                """
                INSERT INTO form_versions (
                    id, form_id, version, status, is_published, is_latest, changelog, created_at, updated_at
                ) VALUES (?, ?, '1', 'PUBLISHED', FALSE, FALSE, 'Initial version',
                          TIMESTAMP WITH TIME ZONE '2026-08-14 09:00:00+00',
                          TIMESTAMP WITH TIME ZONE '2026-08-14 09:00:00+00')
                """,
                version1Id, formId);
        jdbcTemplate.update(
                """
                INSERT INTO form_versions (
                    id, form_id, version, status, is_published, is_latest, changelog, published_at, created_at, updated_at
                ) VALUES (?, ?, '2', 'PUBLISHED', TRUE, TRUE, 'Second version',
                          TIMESTAMP WITH TIME ZONE '2026-08-15 10:00:00+00',
                          TIMESTAMP WITH TIME ZONE '2026-08-15 09:00:00+00',
                          TIMESTAMP WITH TIME ZONE '2026-08-15 09:00:00+00')
                """,
                version2Id, formId);

        jdbcTemplate.update("""
                INSERT INTO form_publish_history (
                    id, form_version_id, action, previous_version_id,
                    performed_by, performed_at, notes
                ) VALUES (?, ?, 'PUBLISHED', NULL, ?, TIMESTAMP WITH TIME ZONE '2026-08-14 10:00:00+00', ?)
                """,
                UUID.randomUUID(), version1Id, userId, "Initial publish");
        jdbcTemplate.update("""
                INSERT INTO form_publish_history (
                    id, form_version_id, action, previous_version_id,
                    performed_by, performed_at, notes
                ) VALUES (?, ?, 'PUBLISHED', ?, ?, TIMESTAMP WITH TIME ZONE '2026-08-15 10:00:00+00', ?)
                """,
                UUID.randomUUID(), version2Id, version1Id, userId, "Publish revision");
    }


    @Test
    void listsVersionSummariesWithoutSerializingJpaEntities() {
        List<FormLifecycleHistoryService.FormVersionView> versions =
                service.listVersions(projectId, formId);

        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).id()).isEqualTo(version2Id);
        assertThat(versions.get(0).version()).isEqualTo("2");
        assertThat(versions.get(0).status()).isEqualTo("PUBLISHED");
        assertThat(versions.get(0).isPublished()).isTrue();
        assertThat(versions.get(0).isLatest()).isTrue();
        assertThat(versions.get(0).changelog()).isEqualTo("Second version");
        assertThat(versions.get(0).publishedAt()).isNotNull();
    }

    @Test
    void listsHistoryNewestFirstWithVersionAndActorDetails() {
        List<FormLifecycleHistoryService.LifecycleHistoryView> history =
                service.listHistory(projectId, formId);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).version()).isEqualTo("2");
        assertThat(history.get(0).previousVersion()).isEqualTo("1");
        assertThat(history.get(0).action()).isEqualTo("PUBLISHED");
        assertThat(history.get(0).performedByUsername()).isEqualTo("admin");
        assertThat(history.get(0).performedByName()).isEqualTo("System Administrator");
        assertThat(history.get(0).notes()).isEqualTo("Publish revision");
        assertThat(history.get(1).version()).isEqualTo("1");
    }

    @Test
    void scopesHistoryToProjectAndForm() {
        assertThat(service.listHistory(UUID.randomUUID(), formId)).isEmpty();
        assertThat(service.listHistory(projectId, UUID.randomUUID())).isEmpty();
    }
}
