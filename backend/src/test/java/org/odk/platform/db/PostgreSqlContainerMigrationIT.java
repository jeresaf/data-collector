package org.odk.platform.db;

import org.junit.jupiter.api.Test;
import java.util.UUID;
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

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

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
    void followUpWorkPersistsDistinctSubmissionAndDurableRecordLinkOnPostgreSql()
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

}
