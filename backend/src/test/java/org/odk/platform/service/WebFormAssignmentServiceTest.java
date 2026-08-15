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

class WebFormAssignmentServiceTest {

    private EmbeddedDatabase database;
    private JdbcTemplate jdbcTemplate;
    private WebFormAssignmentService service;

    private UUID projectId;
    private UUID formId;
    private UUID actorId;
    private UUID directUserId;
    private UUID roleUserId;
    private UUID analystRoleId;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .build();

        jdbcTemplate = new JdbcTemplate(database);
        service = new WebFormAssignmentService(jdbcTemplate);

        createSchema();
        seedData();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void directUserAndRoleAssignmentsResolveEffectiveAccess() {
        WebFormAssignmentService.WebFormAssignmentView direct =
                service.assignUser(projectId, formId, directUserId, actorId);

        WebFormAssignmentService.WebFormAssignmentView role =
                service.assignRole(projectId, formId, analystRoleId, actorId);

        assertThat(direct.targetType()).isEqualTo("USER");
        assertThat(direct.targetName()).isEqualTo("Alice Collector");
        assertThat(role.targetType()).isEqualTo("ROLE");
        assertThat(role.targetName()).isEqualTo("Data Analyst");

        assertThat(service.hasUserAccess(projectId, formId, directUserId)).isTrue();
        assertThat(service.hasUserAccess(projectId, formId, roleUserId)).isTrue();

        List<WebFormAssignmentService.WebFormAssignmentView> assignments =
                service.listAssignments(projectId, formId);
        assertThat(assignments).hasSize(2);
    }

    @Test
    void removedAssignmentIsReactivatedInsteadOfDuplicated() {
        WebFormAssignmentService.WebFormAssignmentView first =
                service.assignUser(projectId, formId, directUserId, actorId);

        service.removeAssignment(projectId, formId, first.id());

        assertThat(service.listAssignments(projectId, formId)).isEmpty();
        assertThat(service.hasUserAccess(projectId, formId, directUserId)).isFalse();

        WebFormAssignmentService.WebFormAssignmentView second =
                service.assignUser(projectId, formId, directUserId, actorId);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(service.listAssignments(projectId, formId)).hasSize(1);

        Long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM web_form_assignments",
                Long.class);
        assertThat(rowCount).isEqualTo(1L);
    }

    @Test
    void candidatesAreProjectMembersAndSystemAdminIsNotAssignable() {
        List<WebFormAssignmentService.UserCandidate> users =
                service.searchUserCandidates(projectId, formId, "alice");

        assertThat(users)
                .extracting(WebFormAssignmentService.UserCandidate::username)
                .containsExactly("alice");

        List<WebFormAssignmentService.RoleCandidate> roles =
                service.listRoleCandidates(projectId, formId);

        assertThat(roles)
                .extracting(WebFormAssignmentService.RoleCandidate::name)
                .contains("DATA_ANALYST")
                .doesNotContain("SYSTEM_ADMIN");
    }


    @Test
    void configurationAndProjectParticipationSupportSecureRollout() {
        assertThat(service.hasAssignmentConfiguration(projectId, formId)).isFalse();

        assertThat(service.isProjectParticipant(projectId, actorId)).isTrue();
        assertThat(service.canManageProjectForms(projectId, actorId)).isTrue();

        assertThat(service.isProjectParticipant(projectId, directUserId)).isTrue();
        assertThat(service.canManageProjectForms(projectId, directUserId)).isFalse();

        WebFormAssignmentService.WebFormAssignmentView assignment =
                service.assignUser(projectId, formId, directUserId, actorId);

        assertThat(service.hasAssignmentConfiguration(projectId, formId)).isTrue();

        service.removeAssignment(projectId, formId, assignment.id());

        // Soft removal must not make the form "never configured" again.
        assertThat(service.hasAssignmentConfiguration(projectId, formId)).isTrue();
        assertThat(service.hasUserAccess(projectId, formId, directUserId)).isFalse();
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE users (
                    id UUID PRIMARY KEY,
                    username VARCHAR(255) NOT NULL UNIQUE,
                    email VARCHAR(255) NOT NULL UNIQUE,
                    first_name VARCHAR(100),
                    last_name VARCHAR(100),
                    is_active BOOLEAN NOT NULL DEFAULT TRUE
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE projects (
                    id UUID PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    owner_id UUID NOT NULL,
                    created_by_id UUID NOT NULL
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE roles (
                    id UUID PRIMARY KEY,
                    name VARCHAR(100) NOT NULL UNIQUE,
                    display_name VARCHAR(255),
                    description VARCHAR(500),
                    is_active BOOLEAN NOT NULL DEFAULT TRUE
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE xforms (
                    id UUID PRIMARY KEY,
                    project_id UUID NOT NULL,
                    title VARCHAR(255) NOT NULL,
                    CONSTRAINT fk_xforms_project
                        FOREIGN KEY (project_id) REFERENCES projects(id)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE project_members (
                    id UUID PRIMARY KEY,
                    project_id UUID NOT NULL,
                    user_id UUID NOT NULL,
                    role_id UUID NOT NULL,
                    assigned_by UUID,
                    is_active BOOLEAN NOT NULL DEFAULT TRUE,
                    CONSTRAINT fk_pm_project FOREIGN KEY (project_id) REFERENCES projects(id),
                    CONSTRAINT fk_pm_user FOREIGN KEY (user_id) REFERENCES users(id),
                    CONSTRAINT fk_pm_role FOREIGN KEY (role_id) REFERENCES roles(id),
                    UNIQUE (project_id, user_id, role_id)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE web_form_assignments (
                    id UUID PRIMARY KEY,
                    project_id UUID NOT NULL,
                    form_id UUID NOT NULL,
                    target_type VARCHAR(20) NOT NULL,
                    target_id UUID NOT NULL,
                    assigned_by UUID,
                    is_active BOOLEAN NOT NULL DEFAULT TRUE,
                    assigned_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT fk_wfa_project FOREIGN KEY (project_id) REFERENCES projects(id),
                    CONSTRAINT fk_wfa_form FOREIGN KEY (form_id) REFERENCES xforms(id),
                    CONSTRAINT fk_wfa_actor FOREIGN KEY (assigned_by) REFERENCES users(id),
                    CONSTRAINT chk_wfa_target CHECK (target_type IN ('USER', 'ROLE')),
                    UNIQUE (project_id, form_id, target_type, target_id)
                )
                """);
    }

    private void seedData() {
        projectId = UUID.randomUUID();
        formId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        directUserId = UUID.randomUUID();
        roleUserId = UUID.randomUUID();
        analystRoleId = UUID.randomUUID();

        UUID collectorRoleId = UUID.randomUUID();
        UUID systemRoleId = UUID.randomUUID();

        insertUser(actorId, "admin", "admin@example.com", "System", "Administrator");
        insertUser(directUserId, "alice", "alice@example.com", "Alice", "Collector");
        insertUser(roleUserId, "bob", "bob@example.com", "Bob", "Analyst");

        jdbcTemplate.update(
                "INSERT INTO projects (id, name, owner_id, created_by_id) VALUES (?, ?, ?, ?)",
                projectId, "Web Assignment Project", actorId, actorId);

        insertRole(analystRoleId, "DATA_ANALYST", "Data Analyst");
        insertRole(collectorRoleId, "DATA_COLLECTOR", "Data Collector");
        insertRole(systemRoleId, "SYSTEM_ADMIN", "System Administrator");

        jdbcTemplate.update(
                "INSERT INTO xforms (id, project_id, title) VALUES (?, ?, ?)",
                formId, projectId, "Household Survey");

        jdbcTemplate.update("""
                INSERT INTO project_members (
                    id, project_id, user_id, role_id, assigned_by, is_active
                ) VALUES (?, ?, ?, ?, ?, TRUE)
                """,
                UUID.randomUUID(), projectId, directUserId, collectorRoleId, actorId);

        jdbcTemplate.update("""
                INSERT INTO project_members (
                    id, project_id, user_id, role_id, assigned_by, is_active
                ) VALUES (?, ?, ?, ?, ?, TRUE)
                """,
                UUID.randomUUID(), projectId, roleUserId, analystRoleId, actorId);
    }

    private void insertUser(
            UUID id,
            String username,
            String email,
            String firstName,
            String lastName) {
        jdbcTemplate.update("""
                INSERT INTO users (
                    id, username, email, first_name, last_name, is_active
                ) VALUES (?, ?, ?, ?, ?, TRUE)
                """, id, username, email, firstName, lastName);
    }

    private void insertRole(UUID id, String name, String displayName) {
        jdbcTemplate.update("""
                INSERT INTO roles (
                    id, name, display_name, description, is_active
                ) VALUES (?, ?, ?, ?, TRUE)
                """, id, name, displayName, displayName + " role");
    }
}
