package org.odk.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.odk.platform.OdkPlatformApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = OdkPlatformApplication.class, properties = "odk.test.project-controller-integration=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProjectControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID testProjectId;

    @BeforeEach
    void setUp() {
        // Create a test project for update/delete tests
        testProjectId = UUID.randomUUID();
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void createProject_ShouldReturnCreatedProject() throws Exception {
        // Given
        Map<String, Object> request = Map.of(
                "name", "Test Project",
                "description", "Test Description"
        );

        // When & Then
        mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Test Project"))
                .andExpect(jsonPath("$.description").value("Test Description"))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.owner").exists())
                .andExpect(jsonPath("$.createdBy").exists());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void createProject_WithMissingName_ShouldReturnBadRequest() throws Exception {
        // Given
        Map<String, Object> request = Map.of(
                "description", "Test Description"
        );

        // When & Then
        mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void getAllProjects_ShouldReturnPagedProjects() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/projects")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.number").exists())
                .andExpect(jsonPath("$.size").exists())
                .andExpect(jsonPath("$.totalElements").exists())
                .andExpect(jsonPath("$.totalPages").exists());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void getProjectById_WhenExists_ShouldReturnProject() throws Exception {
        // Given - create a project first
        Map<String, Object> createRequest = Map.of(
                "name", "Project to Get",
                "description", "Description"
        );

        String createResponse = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        // When & Then
        mockMvc.perform(get("/api/projects/{id}", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId.toString()))
                .andExpect(jsonPath("$.name").value("Project to Get"))
                .andExpect(jsonPath("$.description").value("Description"));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void getProjectById_WhenNotExists_ShouldReturnNotFound() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/projects/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void updateProject_ShouldReturnUpdatedProject() throws Exception {
        // Given - create a project first
        Map<String, Object> createRequest = Map.of(
                "name", "Original Name",
                "description", "Original Description"
        );

        String createResponse = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        // When - update the project
        Map<String, Object> updateRequest = Map.of(
                "name", "Updated Name",
                "description", "Updated Description",
                "isActive", false
        );

        // Then
        mockMvc.perform(put("/api/projects/{id}", projectId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId.toString()))
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.description").value("Updated Description"))
                .andExpect(jsonPath("$.isActive").value(false));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void updateProject_WhenNotExists_ShouldReturnNotFound() throws Exception {
        // Given
        Map<String, Object> updateRequest = Map.of(
                "name", "Updated Name"
        );

        // When & Then
        mockMvc.perform(put("/api/projects/{id}", UUID.randomUUID())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void archiveProject_ShouldArchiveProject() throws Exception {
        // Given - create a project first
        Map<String, Object> createRequest = Map.of(
                "name", "Project to Archive",
                "description", "Description"
        );

        String createResponse = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        // When - archive the project (POST, not DELETE)
        mockMvc.perform(post("/api/projects/{id}/archive", projectId)
                        .with(csrf()))
                .andExpect(status().isOk());

        // Then - verify it's archived (isActive = false)
        mockMvc.perform(get("/api/projects/{id}", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void archiveProject_WhenNotExists_ShouldReturnNotFound() throws Exception {
        // When & Then
        mockMvc.perform(post("/api/projects/{id}/archive", UUID.randomUUID())
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void deleteProject_ShouldDeleteProject() throws Exception {
        // Given - create a project first
        Map<String, Object> createRequest = Map.of(
                "name", "Project to Delete",
                "description", "Description"
        );

        String createResponse = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        // When - delete the project
        mockMvc.perform(delete("/api/projects/{id}", projectId)
                        .with(csrf()))
                .andExpect(status().isOk());

        // Then - verify it's deleted
        mockMvc.perform(get("/api/projects/{id}", projectId))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void deleteProject_WhenNotExists_ShouldReturnNotFound() throws Exception {
        // When & Then
        mockMvc.perform(delete("/api/projects/{id}", UUID.randomUUID())
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void getProjectSettings_ShouldReturnSettings() throws Exception {
        // Given - create a project first
        Map<String, Object> createRequest = Map.of(
                "name", "Project with Settings",
                "description", "Description"
        );

        String createResponse = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        // When & Then
        mockMvc.perform(get("/api/projects/{id}/settings", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isMap());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void updateProjectSetting_ShouldUpdateSetting() throws Exception {
        // Given - create a project first
        Map<String, Object> createRequest = Map.of(
                "name", "Project for Settings",
                "description", "Description"
        );

        String createResponse = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        // When - update a setting
        Map<String, Object> settingRequest = Map.of(
                "value", Map.of("value", "test-value"),
                "description", "Test setting"
        );

        // Then
        mockMvc.perform(put("/api/projects/{id}/settings/{key}", projectId, "test_setting")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(settingRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settingKey").value("test_setting"))
                .andExpect(jsonPath("$.settingValue.value").value("test-value"))
                .andExpect(jsonPath("$.description").value("Test setting"));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void deleteProjectSetting_ShouldDeleteSetting() throws Exception {
        // Given - create a project first
        Map<String, Object> createRequest = Map.of(
                "name", "Project for Delete Setting",
                "description", "Description"
        );

        String createResponse = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        // First create a setting
        Map<String, Object> settingRequest = Map.of(
                "value", Map.of("value", "to-delete"),
                "description", "To be deleted"
        );

        mockMvc.perform(put("/api/projects/{id}/settings/{key}", projectId, "to_delete")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(settingRequest)))
                .andExpect(status().isOk());

        // When - delete the setting
        mockMvc.perform(delete("/api/projects/{id}/settings/{key}", projectId, "to_delete")
                        .with(csrf()))
                .andExpect(status().isOk());

        // Then - verify it's deleted
        mockMvc.perform(get("/api/projects/{id}/settings/{key}", projectId, "to_delete"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"PROJECT_MANAGER"})
    void createProject_AsProjectManager_ShouldWork() throws Exception {
        // Given
        Map<String, Object> request = Map.of(
                "name", "User Project",
                "description", "User Description"
        );

        // When & Then
        mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("User Project"));
    }

    @Test
    void createProject_WithoutAuth_ShouldReturnUnauthorized() throws Exception {
        // Given
        Map<String, Object> request = Map.of(
                "name", "Unauthorized Project",
                "description", "Description"
        );

        // When & Then
        mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // ==================== Form Upload Tests ====================

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void uploadXForm_ValidXml_ShouldUploadAndValidate() throws Exception {
        // Given - create a project first
        Map<String, Object> createRequest = Map.of(
                "name", "Project for Form Upload",
                "description", "Description"
        );

        String createResponse = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        // Valid XForm XML
        String validXFormXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<h:html xmlns:h=\"http://www.w3.org/1999/xhtml\" xmlns:orx=\"http://openrosa.org/xforms\">\n" +
                "  <h:head>\n" +
                "    <h:title>Test Form</h:title>\n" +
                "    <model>\n" +
                "      <instance>\n" +
                "        <data id=\"test_form\">\n" +
                "          <meta>\n" +
                "            <instanceID/>\n" +
                "          </meta>\n" +
                "          <name/>\n" +
                "          <age/>\n" +
                "        </data>\n" +
                "      </instance>\n" +
                "      <bind nodeset=\"/data/name\" type=\"string\"/>\n" +
                "      <bind nodeset=\"/data/age\" type=\"int\"/>\n" +
                "    </model>\n" +
                "  </h:head>\n" +
                "  <h:body>\n" +
                "    <input ref=\"/data/name\">\n" +
                "      <label>Name</label>\n" +
                "    </input>\n" +
                "    <input ref=\"/data/age\">\n" +
                "      <label>Age</label>\n" +
                "    </input>\n" +
                "  </h:body>\n" +
                "</h:html>";

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test_form.xml",
                MediaType.APPLICATION_XML_VALUE,
                validXFormXml.getBytes()
        );

        // When & Then
        mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", projectId)
                        .file(file)
                        .with(csrf()))
                .andExpect(result -> assertEquals(
                        200,
                        result.getResponse().getStatus(),
                        "XForm upload failed. body="
                                + result.getResponse().getContentAsString()
                                + "; resolvedException="
                                + (result.getResolvedException() == null
                                    ? "<none>"
                                    : result.getResolvedException().getClass().getName()
                                        + ": " + result.getResolvedException().getMessage())))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.title").value("Test Form"))
                .andExpect(jsonPath("$.xmlns").value("test_form"))
                .andExpect(jsonPath("$.version").value("1.0"))
                .andExpect(jsonPath("$.xmlContent").exists())
                .andExpect(jsonPath("$.md5Hash").exists())
                .andExpect(jsonPath("$.isPublished").value(false))
                .andExpect(jsonPath("$.isActive").value(true));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void uploadXForm_InvalidXml_ShouldReturnBadRequest() throws Exception {
        // Given - create a project first
        Map<String, Object> createRequest = Map.of(
                "name", "Project for Invalid Form",
                "description", "Description"
        );

        String createResponse = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        // Invalid XForm XML (missing instance)
        String invalidXFormXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<h:html xmlns:h=\"http://www.w3.org/1999/xhtml\" xmlns:orx=\"http://openrosa.org/xforms\">\n" +
                "  <h:head>\n" +
                "    <h:title>Invalid Form</h:title>\n" +
                "    <model>\n" +
                "    </model>\n" +
                "  </h:head>\n" +
                "  <h:body>\n" +
                "  </h:body>\n" +
                "</h:html>";

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "invalid_form.xml",
                MediaType.APPLICATION_XML_VALUE,
                invalidXFormXml.getBytes()
        );

        // When & Then
        mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", projectId)
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void uploadXForm_MissingFile_ShouldReturnBadRequest() throws Exception {
        // Given - create a project first
        Map<String, Object> createRequest = Map.of(
                "name", "Project for Missing File",
                "description", "Description"
        );

        String createResponse = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        // When & Then - no file provided
        mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", projectId)
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void uploadXForm_ProjectNotFound_ShouldReturnNotFound() throws Exception {
        // Given
        UUID nonExistentProjectId = UUID.randomUUID();
        String validXFormXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<h:html xmlns:h=\"http://www.w3.org/1999/xhtml\" xmlns:orx=\"http://openrosa.org/xforms\">\n" +
                "  <h:head>\n" +
                "    <h:title>Test Form</h:title>\n" +
                "    <model>\n" +
                "      <instance>\n" +
                "        <data id=\"test_form\">\n" +
                "          <meta>\n" +
                "            <instanceID/>\n" +
                "          </meta>\n" +
                "          <name/>\n" +
                "        </data>\n" +
                "      </instance>\n" +
                "    </model>\n" +
                "  </h:head>\n" +
                "  <h:body>\n" +
                "    <input ref=\"/data/name\">\n" +
                "      <label>Name</label>\n" +
                "    </input>\n" +
                "  </h:body>\n" +
                "</h:html>";

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test_form.xml",
                MediaType.APPLICATION_XML_VALUE,
                validXFormXml.getBytes()
        );

        // When & Then
        mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", nonExistentProjectId)
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void uploadXForm_DuplicateFormId_ShouldReturnConflict() throws Exception {
        // Given - create a project first
        Map<String, Object> createRequest = Map.of(
                "name", "Project for Duplicate Form",
                "description", "Description"
        );

        String createResponse = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        // Valid XForm XML
        String validXFormXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<h:html xmlns:h=\"http://www.w3.org/1999/xhtml\" xmlns:orx=\"http://openrosa.org/xforms\">\n" +
                "  <h:head>\n" +
                "    <h:title>Duplicate Form</h:title>\n" +
                "    <model>\n" +
                "      <instance>\n" +
                "        <data id=\"duplicate_form\">\n" +
                "          <meta>\n" +
                "            <instanceID/>\n" +
                "          </meta>\n" +
                "          <name/>\n" +
                "        </data>\n" +
                "      </instance>\n" +
                "    </model>\n" +
                "  </h:head>\n" +
                "  <h:body>\n" +
                "    <input ref=\"/data/name\">\n" +
                "      <label>Name</label>\n" +
                "    </input>\n" +
                "  </h:body>\n" +
                "</h:html>";

        MockMultipartFile file1 = new MockMultipartFile(
                "file",
                "duplicate_form.xml",
                MediaType.APPLICATION_XML_VALUE,
                validXFormXml.getBytes()
        );

        MockMultipartFile file2 = new MockMultipartFile(
                "file",
                "duplicate_form2.xml",
                MediaType.APPLICATION_XML_VALUE,
                validXFormXml.getBytes()
        );

        // When - upload first form
        mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", projectId)
                        .file(file1)
                        .with(csrf()))
                .andExpect(result -> assertEquals(
                        200,
                        result.getResponse().getStatus(),
                        "Initial duplicate-test upload failed. body="
                                + result.getResponse().getContentAsString()
                                + "; resolvedException="
                                + (result.getResolvedException() == null
                                    ? "<none>"
                                    : result.getResolvedException().getClass().getName()
                                        + ": " + result.getResolvedException().getMessage())));

        // Then - upload second form with same form ID should fail
        mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", projectId)
                        .file(file2)
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void getFormsByProject_ShouldReturnForms() throws Exception {
        // Given - create a project first
        Map<String, Object> createRequest = Map.of(
                "name", "Project for Form List",
                "description", "Description"
        );

        String createResponse = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        // Upload a form first
        String validXFormXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<h:html xmlns:h=\"http://www.w3.org/1999/xhtml\" xmlns:orx=\"http://openrosa.org/xforms\">\n" +
                "  <h:head>\n" +
                "    <h:title>List Form</h:title>\n" +
                "    <model>\n" +
                "      <instance>\n" +
                "        <data id=\"list_form\">\n" +
                "          <meta>\n" +
                "            <instanceID/>\n" +
                "          </meta>\n" +
                "          <name/>\n" +
                "        </data>\n" +
                "      </instance>\n" +
                "    </model>\n" +
                "  </h:head>\n" +
                "  <h:body>\n" +
                "    <input ref=\"/data/name\">\n" +
                "      <label>Name</label>\n" +
                "    </input>\n" +
                "  </h:body>\n" +
                "</h:html>";

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "list_form.xml",
                MediaType.APPLICATION_XML_VALUE,
                validXFormXml.getBytes()
        );

        mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", projectId)
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isOk());

        // When & Then - get forms list
        mockMvc.perform(get("/api/projects/{projectId}/forms", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("List Form"))
                .andExpect(jsonPath("$.content[0].xmlns").value("list_form"));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void getFormById_ShouldReturnForm() throws Exception {
        // Given - create a project first
        Map<String, Object> createRequest = Map.of(
                "name", "Project for Get Form",
                "description", "Description"
        );

        String createResponse = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        // Upload a form first
        String validXFormXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<h:html xmlns:h=\"http://www.w3.org/1999/xhtml\" xmlns:orx=\"http://openrosa.org/xforms\">\n" +
                "  <h:head>\n" +
                "    <h:title>Get Form</h:title>\n" +
                "    <model>\n" +
                "      <instance>\n" +
                "        <data id=\"get_form\">\n" +
                "          <meta>\n" +
                "            <instanceID/>\n" +
                "          </meta>\n" +
                "          <name/>\n" +
                "        </data>\n" +
                "      </instance>\n" +
                "    </model>\n" +
                "  </h:head>\n" +
                "  <h:body>\n" +
                "    <input ref=\"/data/name\">\n" +
                "      <label>Name</label>\n" +
                "    </input>\n" +
                "  </h:body>\n" +
                "</h:html>";

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "get_form.xml",
                MediaType.APPLICATION_XML_VALUE,
                validXFormXml.getBytes()
        );

        String uploadResponse = mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", projectId)
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID formId = UUID.fromString(objectMapper.readTree(uploadResponse).get("id").asText());

        // When & Then - get form by ID
        mockMvc.perform(get("/api/projects/{projectId}/forms/{formId}", projectId, formId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(formId.toString()))
                .andExpect(jsonPath("$.title").value("Get Form"))
                .andExpect(jsonPath("$.xmlns").value("get_form"))
                .andExpect(jsonPath("$.xmlContent").exists());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void getFormById_NotFound_ShouldReturnNotFound() throws Exception {
        // Given - create a project first
        Map<String, Object> createRequest = Map.of(
                "name", "Project for Not Found Form",
                "description", "Description"
        );

        String createResponse = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        // When & Then
        mockMvc.perform(get("/api/projects/{projectId}/forms/{formId}", projectId, UUID.randomUUID()))
                .andExpect(result -> assertEquals(
                        404,
                        result.getResponse().getStatus(),
                        "Missing-form lookup returned the wrong status. body="
                                + result.getResponse().getContentAsString()
                                + "; resolvedException="
                                + (result.getResolvedException() == null
                                    ? "<none>"
                                    : result.getResolvedException().getClass().getName()
                                        + ": " + result.getResolvedException().getMessage())));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void deleteForm_ShouldDeleteForm() throws Exception {
        // Given - create a project first
        Map<String, Object> createRequest = Map.of(
                "name", "Project for Delete Form",
                "description", "Description"
        );

        String createResponse = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        // Upload a form first
        String validXFormXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<h:html xmlns:h=\"http://www.w3.org/1999/xhtml\" xmlns:orx=\"http://openrosa.org/xforms\">\n" +
                "  <h:head>\n" +
                "    <h:title>Delete Form</h:title>\n" +
                "    <model>\n" +
                "      <instance>\n" +
                "        <data id=\"delete_form\">\n" +
                "          <meta>\n" +
                "            <instanceID/>\n" +
                "          </meta>\n" +
                "          <name/>\n" +
                "        </data>\n" +
                "      </instance>\n" +
                "    </model>\n" +
                "  </h:head>\n" +
                "  <h:body>\n" +
                "    <input ref=\"/data/name\">\n" +
                "      <label>Name</label>\n" +
                "    </input>\n" +
                "  </h:body>\n" +
                "</h:html>";

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "delete_form.xml",
                MediaType.APPLICATION_XML_VALUE,
                validXFormXml.getBytes()
        );

        String uploadResponse = mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", projectId)
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID formId = UUID.fromString(objectMapper.readTree(uploadResponse).get("id").asText());

        // When - delete the form
        mockMvc.perform(delete("/api/projects/{projectId}/forms/{formId}", projectId, formId)
                        .with(csrf()))
                .andExpect(status().isOk());

        // Then - verify it's deleted
        mockMvc.perform(get("/api/projects/{projectId}/forms/{formId}", projectId, formId))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void validateForm_ValidXml_ShouldReturnValid() throws Exception {
        // Given - create a project first
        Map<String, Object> createRequest = Map.of(
                "name", "Project for Validation",
                "description", "Description"
        );

        String createResponse = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        // Valid XForm XML
        String validXFormXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<h:html xmlns:h=\"http://www.w3.org/1999/xhtml\" xmlns:orx=\"http://openrosa.org/xforms\">\n" +
                "  <h:head>\n" +
                "    <h:title>Valid Form</h:title>\n" +
                "    <model>\n" +
                "      <instance>\n" +
                "        <data id=\"valid_form\">\n" +
                "          <meta>\n" +
                "            <instanceID/>\n" +
                "          </meta>\n" +
                "          <name/>\n" +
                "        </data>\n" +
                "      </instance>\n" +
                "    </model>\n" +
                "  </h:head>\n" +
                "  <h:body>\n" +
                "    <input ref=\"/data/name\">\n" +
                "      <label>Name</label>\n" +
                "    </input>\n" +
                "  </h:body>\n" +
                "</h:html>";

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "valid_form.xml",
                MediaType.APPLICATION_XML_VALUE,
                validXFormXml.getBytes()
        );

        // When & Then - validate form
        mockMvc.perform(multipart("/api/projects/{projectId}/forms/validate", projectId)
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.info").isArray());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void validateForm_InvalidXml_ShouldReturnInvalid() throws Exception {
        // Given - create a project first
        Map<String, Object> createRequest = Map.of(
                "name", "Project for Invalid Validation",
                "description", "Description"
        );

        String createResponse = mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        UUID projectId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        // Invalid XForm XML (missing instance)
        String invalidXFormXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<h:html xmlns:h=\"http://www.w3.org/1999/xhtml\" xmlns:orx=\"http://openrosa.org/xforms\">\n" +
                "  <h:head>\n" +
                "    <h:title>Invalid Form</h:title>\n" +
                "    <model>\n" +
                "    </model>\n" +
                "  </h:head>\n" +
                "  <h:body>\n" +
                "  </h:body>\n" +
                "</h:html>";

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "invalid_form.xml",
                MediaType.APPLICATION_XML_VALUE,
                invalidXFormXml.getBytes()
        );

        // When & Then - validate form
        mockMvc.perform(multipart("/api/projects/{projectId}/forms/validate", projectId)
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors.length()").value(greaterThan(0)));
    }

    @Test
    void uploadForm_WithoutAuth_ShouldReturnUnauthorized() throws Exception {
        // Given
        UUID projectId = UUID.randomUUID();
        String validXFormXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<h:html xmlns:h=\"http://www.w3.org/1999/xhtml\" xmlns:orx=\"http://openrosa.org/xforms\">\n" +
                "  <h:head>\n" +
                "    <h:title>Test Form</h:title>\n" +
                "    <model>\n" +
                "      <instance>\n" +
                "        <data id=\"test_form\">\n" +
                "          <meta>\n" +
                "            <instanceID/>\n" +
                "          </meta>\n" +
                "          <name/>\n" +
                "        </data>\n" +
                "      </instance>\n" +
                "    </model>\n" +
                "  </h:head>\n" +
                "  <h:body>\n" +
                "    <input ref=\"/data/name\">\n" +
                "      <label>Name</label>\n" +
                "    </input>\n" +
                "  </h:body>\n" +
                "</h:html>";

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test_form.xml",
                MediaType.APPLICATION_XML_VALUE,
                validXFormXml.getBytes()
        );

        // When & Then
        mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", projectId)
                        .file(file))
                .andExpect(status().isUnauthorized());
    }
}
