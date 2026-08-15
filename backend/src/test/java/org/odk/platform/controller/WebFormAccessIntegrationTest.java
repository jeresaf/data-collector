package org.odk.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.User;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.UserRepository;
import org.odk.platform.repository.XFormRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WebFormAccessIntegrationTest {

    private static final String XFORM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms">
              <h:head>
                <h:title>Access Form</h:title>
                <model>
                  <instance>
                    <data id="access_form">
                      <name/>
                      <meta><instanceID/></meta>
                    </data>
                  </instance>
                  <bind nodeset="/data/name" type="string"/>
                </model>
              </h:head>
              <h:body>
                <input ref="/data/name"><label>Name</label></input>
              </h:body>
            </h:html>
            """;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired XFormRepository xFormRepository;
    @Autowired FormVersionRepository formVersionRepository;

    private UUID ownerId;
    private UUID memberId;
    private UUID outsiderId;
    private UUID projectId;
    private UUID formId;
    private UUID formVersionId;
    private UUID collectorRoleId;

    @BeforeEach
    void setUp() {
        User owner = createUser("web-access-owner");
        ownerId = owner.getId();

        User member = createUser("web-access-member");
        memberId = member.getId();

        User outsider = createUser("web-access-outsider");
        outsiderId = outsider.getId();

        Project project = Project.builder()
                .name("Web Access Project")
                .owner(owner)
                .createdBy(owner)
                .build();
        project = projectRepository.saveAndFlush(project);
        projectId = project.getId();

        XForm form = XForm.builder()
                .title("Access Form")
                .xmlns("access_form")
                .version("1")
                .xmlContent(XFORM)
                .project(project)
                .createdBy(owner)
                .build();
        form = xFormRepository.saveAndFlush(form);
        formId = form.getId();

        FormVersion version = FormVersion.builder()
                .form(form)
                .version("1")
                .xmlContent(XFORM)
                .createdBy(owner)
                .build();
        version = formVersionRepository.saveAndFlush(version);
        formVersionId = version.getId();

        collectorRoleId = jdbcTemplate.queryForObject(
                "SELECT id FROM roles WHERE name = 'DATA_COLLECTOR'",
                UUID.class);

        jdbcTemplate.update("""
                INSERT INTO project_members (
                    id, project_id, user_id, role_id, assigned_by,
                    is_active, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                projectId,
                memberId,
                collectorRoleId,
                ownerId);
    }

    @Test
    @WithMockUser(username = "web-access-owner", roles = {"VIEWER"})
    void projectOwnerCanStartUnconfiguredFormEvenWithNonLegacyRole() throws Exception {
        mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").exists());
    }

    @Test
    @WithMockUser(username = "web-access-owner", roles = {"VIEWER"})
    void clientCannotSpoofAnotherUserId() throws Exception {
        WebFormController.StartSessionRequest request =
                new WebFormController.StartSessionRequest();
        request.setUserId(memberId);

        mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("another user")));
    }

    @Test
    @WithMockUser(username = "web-access-outsider", roles = {"VIEWER"})
    void nonParticipantCannotStartUnconfiguredProjectForm() throws Exception {
        mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "web-access-member", roles = {"DATA_COLLECTOR"})
    void directAssignmentAllowsProjectMemberToStartConfiguredForm() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO web_form_assignments (
                    id, project_id, form_id, target_type, target_id,
                    assigned_by, is_active, assigned_at, created_at, updated_at
                ) VALUES (?, ?, ?, 'USER', ?, ?, TRUE,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                projectId,
                formId,
                memberId,
                ownerId);

        mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").exists());
    }

    @Test
    @WithMockUser(username = "web-access-member", roles = {"DATA_COLLECTOR"})
    void roleAssignmentAllowsMatchingProjectMember() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO web_form_assignments (
                    id, project_id, form_id, target_type, target_id,
                    assigned_by, is_active, assigned_at, created_at, updated_at
                ) VALUES (?, ?, ?, 'ROLE', ?, ?, TRUE,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                projectId,
                formId,
                collectorRoleId,
                ownerId);

        mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").exists());
    }

    @Test
    @WithMockUser(username = "web-access-member", roles = {"DATA_COLLECTOR"})
    void inactivePriorConfigurationKeepsFormRestricted() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO web_form_assignments (
                    id, project_id, form_id, target_type, target_id,
                    assigned_by, is_active, assigned_at, created_at, updated_at
                ) VALUES (?, ?, ?, 'USER', ?, ?, FALSE,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                projectId,
                formId,
                outsiderId,
                ownerId);

        mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("not assigned")));
    }

    private User createUser(String username) {
        User user = User.builder()
                .username(username)
                .email(username + "@example.com")
                .passwordHash("hashed")
                .isActive(true)
                .isVerified(true)
                .build();
        return userRepository.saveAndFlush(user);
    }
}
