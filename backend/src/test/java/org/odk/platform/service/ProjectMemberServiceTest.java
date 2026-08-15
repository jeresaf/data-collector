package org.odk.platform.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.audit.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProjectMemberServiceTest {

    @Mock
    private AuditService auditService;

    private EmbeddedDatabase database;
    private JdbcTemplate jdbcTemplate;
    private ProjectMemberService service;

    private UUID projectId;
    private UUID adminId;
    private UUID userId;
    private UUID roleId;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .build();

        jdbcTemplate = new JdbcTemplate(database);
        service = new ProjectMemberService(jdbcTemplate, auditService);

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
                CREATE TABLE projects (
                    id UUID PRIMARY KEY,
                    name VARCHAR(255) NOT NULL
                )
                """);

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
                CREATE TABLE roles (
                    id UUID PRIMARY KEY,
                    name VARCHAR(100) NOT NULL UNIQUE,
                    display_name VARCHAR(255),
                    description VARCHAR(500),
                    is_active BOOLEAN NOT NULL DEFAULT TRUE
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
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT fk_project_members_project
                        FOREIGN KEY (project_id) REFERENCES projects(id),
                    CONSTRAINT fk_project_members_user
                        FOREIGN KEY (user_id) REFERENCES users(id),
                    CONSTRAINT fk_project_members_role
                        FOREIGN KEY (role_id) REFERENCES roles(id),
                    CONSTRAINT fk_project_members_assigned_by
                        FOREIGN KEY (assigned_by) REFERENCES users(id),
                    CONSTRAINT uk_project_members
                        UNIQUE (project_id, user_id, role_id)
                )
                """);
    }

    private void seedData() {
        projectId = UUID.randomUUID();
        adminId = UUID.randomUUID();
        userId = UUID.randomUUID();
        roleId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO projects (id, name) VALUES (?, ?)",
                projectId,
                "Certification Project"
        );

        jdbcTemplate.update("""
                INSERT INTO users (
                    id,
                    username,
                    email,
                    first_name,
                    last_name,
                    is_active
                ) VALUES (?, ?, ?, ?, ?, TRUE)
                """,
                adminId,
                "admin",
                "admin@example.com",
                "System",
                "Administrator"
        );

        jdbcTemplate.update("""
                INSERT INTO users (
                    id,
                    username,
                    email,
                    first_name,
                    last_name,
                    is_active
                ) VALUES (?, ?, ?, ?, ?, TRUE)
                """,
                userId,
                "analyst",
                "analyst@example.com",
                "Data",
                "Analyst"
        );

        jdbcTemplate.update("""
                INSERT INTO roles (
                    id,
                    name,
                    display_name,
                    description,
                    is_active
                ) VALUES (?, ?, ?, ?, TRUE)
                """,
                roleId,
                "DATA_ANALYST",
                "Data Analyst",
                "Read and analyze project data"
        );
    }

    @Test
    void addListAndRemoveMember() {
        ProjectMemberService.ProjectMemberView added =
                service.addMember(projectId, userId, roleId, adminId);

        assertThat(added.id()).isNotNull();
        assertThat(added.projectId()).isEqualTo(projectId);
        assertThat(added.userId()).isEqualTo(userId);
        assertThat(added.username()).isEqualTo("analyst");
        assertThat(added.email()).isEqualTo("analyst@example.com");
        assertThat(added.fullName()).isEqualTo("Data Analyst");
        assertThat(added.roleId()).isEqualTo(roleId);
        assertThat(added.roleName()).isEqualTo("DATA_ANALYST");
        assertThat(added.roleDisplayName()).isEqualTo("Data Analyst");

        List<ProjectMemberService.ProjectMemberView> members =
                service.listMembers(projectId);

        assertThat(members).hasSize(1);
        assertThat(members.get(0).id()).isEqualTo(added.id());

        service.removeMember(projectId, added.id());

        assertThat(service.listMembers(projectId)).isEmpty();

        verify(auditService).log(
                eq("PROJECT_MEMBER_ASSIGNED"),
                eq("PROJECT_MEMBER"),
                eq(added.id()),
                eq("analyst"),
                anyMap()
        );

        verify(auditService).log(
                eq("PROJECT_MEMBER_REMOVED"),
                eq("PROJECT_MEMBER"),
                eq(added.id()),
                eq("analyst"),
                anyMap()
        );
    }

    @Test
    void reAddingRemovedMembershipReactivatesExistingRow() {
        ProjectMemberService.ProjectMemberView first =
                service.addMember(projectId, userId, roleId, adminId);

        service.removeMember(projectId, first.id());

        ProjectMemberService.ProjectMemberView second =
                service.addMember(projectId, userId, roleId, adminId);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(service.listMembers(projectId)).hasSize(1);

        Boolean active = jdbcTemplate.queryForObject(
                "SELECT is_active FROM project_members WHERE id = ?",
                Boolean.class,
                first.id()
        );

        assertThat(active).isTrue();
    }

    @Test
    void searchesCandidatesAndReturnsOnlyAssignableProjectRoles() {
        UUID systemRoleId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO roles (
                    id,
                    name,
                    display_name,
                    description,
                    is_active
                ) VALUES (?, ?, ?, ?, TRUE)
                """,
                systemRoleId,
                "SYSTEM_ADMIN",
                "System Administrator",
                "System-wide access"
        );

        List<ProjectMemberService.UserCandidate> candidates =
                service.searchCandidates(projectId, "analyst");

        assertThat(candidates)
                .extracting(ProjectMemberService.UserCandidate::username)
                .containsExactly("analyst");

        List<ProjectMemberService.ProjectRoleOption> roles =
                service.listAssignableRoles();

        assertThat(roles)
                .extracting(ProjectMemberService.ProjectRoleOption::name)
                .contains("DATA_ANALYST")
                .doesNotContain("SYSTEM_ADMIN");
    }
}
