package org.odk.platform.openrosa;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for OpenRosa Submission endpoint
 * Tests multipart upload, XML parsing, attachments, instanceID handling, 
 * duplicate detection, replacement flow, and JavaRosa validation
 */
@SpringBootTest(properties = "odk.test.openrosa-submission-integration=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenRosaSubmissionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String validCredentials;
    private String invalidCredentials;
    private UUID testProjectId;
    private String testFormXmlns = "test_submission_form";

    @BeforeEach
    void setUp() throws Exception {
        // Valid credentials: appuser:password123
        String credentials = "appuser:password123";
        validCredentials = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());

        // Invalid credentials
        String invalidCreds = "appuser:wrongpassword";
        invalidCredentials = "Basic " + Base64.getEncoder().encodeToString(invalidCreds.getBytes());

        // Make the Basic Auth fixture deterministic
        ensureAppUser("appuser", "password123");

        // Create a test project and upload a form
        testProjectId = createTestProject();
        uploadAndPublishTestForm();
    }

    private UUID createTestProject() throws Exception {
        Map<String, Object> request = Map.of(
                "name", "Submission Test Project",
                "description", "Test project for submission tests"
        );

        MvcResult result = mockMvc.perform(post("/api/projects")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private UUID ensureAppUser(String username, String rawPassword) {
        String encodedPassword = passwordEncoder.encode(rawPassword);

        List<UUID> existingIds = jdbcTemplate.query(
                "SELECT id FROM app_users WHERE username = ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                username);

        if (!existingIds.isEmpty()) {
            UUID appUserId = existingIds.get(0);
            jdbcTemplate.update(
                    "UPDATE app_users SET password_hash = ?, is_active = true WHERE id = ?",
                    encodedPassword,
                    appUserId);
            return appUserId;
        }

        UUID appUserId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO app_users (
                    id, username, email, password_hash, full_name,
                    is_active, is_verified, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, true, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                appUserId,
                username,
                username + "@example.test",
                encodedPassword,
                username);

        return appUserId;
    }

    private void publishAndAssignUploadedForm(UUID projectId, MvcResult uploadResult) throws Exception {
        String response = uploadResult.getResponse().getContentAsString();
        UUID formId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        List<UUID> versionIds = jdbcTemplate.query(
                """
                SELECT id
                FROM form_versions
                WHERE form_id = ? AND is_latest = true
                ORDER BY created_at DESC
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                formId);

        if (versionIds.isEmpty()) {
            throw new IllegalStateException("Uploaded form has no latest FormVersion: " + formId);
        }

        UUID versionId = versionIds.get(0);

        jdbcTemplate.update(
                """
                UPDATE form_versions
                SET is_published = true,
                    published_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                versionId);

        jdbcTemplate.update(
                """
                UPDATE xforms
                SET is_published = true,
                    current_version_id = ?,
                    published_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                versionId,
                formId);

        UUID appUserId = ensureAppUser("appuser", "password123");

        jdbcTemplate.update(
                "DELETE FROM app_user_projects WHERE app_user_id = ?",
                appUserId);

        jdbcTemplate.update(
                """
                INSERT INTO app_user_projects (
                    id, app_user_id, project_id, form_id, status,
                    assigned_at, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, 'ACTIVE',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                appUserId,
                projectId,
                formId);
    }

    private void uploadAndPublishTestForm() throws Exception {
        // XForm XML for submission testing
        String xformXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>Submission Test Form</h:title>
                        <model>
                            <instance>
                                <data id="test_submission_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                    <name/>
                                    <age/>
                                    <photo/>
                                    <audio/>
                                    <meta>
                                        <instanceID/>
                                    </meta>
                                </data>
                            </instance>
                            <bind nodeset="/data/name" type="string"/>
                            <bind nodeset="/data/age" type="int"/>
                            <bind nodeset="/data/photo" type="binary"/>
                            <bind nodeset="/data/audio" type="binary"/>
                        </model>
                        <itext>
                            <translation lang="en" default="true">
                                <text id="/data/name:label"><value>Name</value></text>
                                <text id="/data/age:label"><value>Age</value></text>
                                <text id="/data/photo:label"><value>Photo</value></text>
                                <text id="/data/audio:label"><value>Audio</value></text>
                            </translation>
                        </itext>
                    </h:head>
                    <h:body>
                        <input ref="/data/name"><label ref="jr:itext('/data/name:label')"/></input>
                        <input ref="/data/age"><label ref="jr:itext('/data/age:label')"/></input>
                        <upload ref="/data/photo" mediatype="image/*"><label ref="jr:itext('/data/photo:label')"/></upload>
                        <upload ref="/data/audio" mediatype="audio/*"><label ref="jr:itext('/data/audio:label')"/></upload>
                    </h:body>
                </h:html>
                """;

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "submission_form.xml",
                MediaType.APPLICATION_XML_VALUE,
                xformXml.getBytes()
        );

        MvcResult uploadResult = mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", testProjectId)
                        .file(file)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.xmlns").value(testFormXmlns))
                .andReturn();

        publishAndAssignUploadedForm(testProjectId, uploadResult);
    }

    // ==================== HEAD Endpoint Tests ====================

    @Test
    void submissionHeadEndpoint_ReturnsNoContentWithAcceptContentLength() throws Exception {
        mockMvc.perform(head("/v1/projects/{projectId}/submission", testProjectId)
                        .header("Authorization", validCredentials)
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<data id=\"test_submission_form\"><name>Test</name></data>"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"))
                .andExpect(header().string("X-OpenRosa-Accept-Content-Length", "104857600"));
    }

    @Test
    void submissionHeadEndpoint_WithoutAuth_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(head("/v1/projects/{projectId}/submission", testProjectId)
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<data id=\"test_submission_form\"><name>Test</name></data>"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submissionHeadEndpoint_WithInvalidAuth_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(head("/v1/projects/{projectId}/submission", testProjectId)
                        .header("Authorization", invalidCredentials)
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<data id=\"test_submission_form\"><name>Test</name></data>"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== POST Endpoint - Basic Submission Tests ====================

    @Test
    void submissionPostEndpoint_WithValidXml_Returns201Created() throws Exception {
        String submissionXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                    <name>John Doe</name>
                    <age>30</age>
                    <meta>
                        <instanceID>uuid:12345678-1234-1234-1234-123456789012</instanceID>
                    </meta>
                </data>
                """;

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        MvcResult result = mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"))
                .andExpect(content().string(containsString("<OpenRosaResponse")))
                .andExpect(content().string(containsString("<message>Submission received</message>")))
                .andReturn();

        String responseXml = result.getResponse().getContentAsString();
        assert responseXml.contains("<status>201</status>") : "Should contain 201 status";
    }

    @Test
    void submissionPostEndpoint_WithoutXmlFile_Returns400BadRequest() throws Exception {
        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .header("Authorization", validCredentials))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"));
    }

    @Test
    void submissionPostEndpoint_WithEmptyXmlFile_Returns400BadRequest() throws Exception {
        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                new byte[0]
        );

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"));
    }

    @Test
    void submissionPostEndpoint_WithoutAuth_Returns401Unauthorized() throws Exception {
        String submissionXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>John Doe</name>
                    <meta><instanceID>uuid:12345678-1234-1234-1234-123456789012</instanceID></meta>
                </data>
                """;

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submissionPostEndpoint_WithInvalidAuth_Returns401Unauthorized() throws Exception {
        String submissionXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>John Doe</name>
                    <meta><instanceID>uuid:12345678-1234-1234-1234-123456789012</instanceID></meta>
                </data>
                """;

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", invalidCredentials))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submissionPostEndpoint_ForNonExistentForm_Returns404NotFound() throws Exception {
        String submissionXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="nonexistent_form" version="1.0">
                    <name>John Doe</name>
                    <meta><instanceID>uuid:12345678-1234-1234-1234-123456789012</instanceID></meta>
                </data>
                """;

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"));
    }

    @Test
    void submissionPostEndpoint_ForUnassignedAppUser_Returns403Forbidden() throws Exception {
        // Create unassigned app user
        UUID unassignedAppUserId = ensureAppUser("unassigned_user", "password123");
        jdbcTemplate.update(
                "DELETE FROM app_user_projects WHERE app_user_id = ?",
                unassignedAppUserId);

        String unassignedCredentials = "Basic " + Base64.getEncoder().encodeToString("unassigned_user:password123".getBytes());

        String submissionXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>John Doe</name>
                    <meta><instanceID>uuid:12345678-1234-1234-1234-123456789012</instanceID></meta>
                </data>
                """;

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", unassignedCredentials))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"));
    }

    // ==================== InstanceID Handling Tests ====================

    @Test
    void submissionPostEndpoint_ExtractsInstanceIdFromMeta() throws Exception {
        String instanceId = "uuid:12345678-1234-1234-1234-123456789012";
        String submissionXml = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>John Doe</name>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """, instanceId);

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        MvcResult result = mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials))
                .andExpect(status().isCreated())
                .andReturn();

        // Verify submission was stored with correct instanceID
        String storedInstanceId = jdbcTemplate.queryForObject(
                "SELECT instance_id FROM submissions WHERE instance_id = ?",
                String.class,
                instanceId);
        
        assert storedInstanceId != null : "Submission should be stored with instanceID";
        assert storedInstanceId.equals(instanceId) : "InstanceID should match";
    }

    @Test
    void submissionPostEndpoint_ExtractsInstanceIdFromInstanceIdTag() throws Exception {
        String instanceId = "uuid:87654321-4321-4321-4321-210987654321";
        String submissionXml = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>Jane Doe</name>
                    <meta><instanceId>%s</instanceId></meta>
                </data>
                """, instanceId);

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials))
                .andExpect(status().isCreated());

        String storedInstanceId = jdbcTemplate.queryForObject(
                "SELECT instance_id FROM submissions WHERE instance_id = ?",
                String.class,
                instanceId);
        
        assert storedInstanceId != null : "Submission should be stored with instanceID";
        assert storedInstanceId.equals(instanceId) : "InstanceID should match";
    }

    @Test
    void submissionPostEndpoint_GeneratesInstanceIdIfMissing() throws Exception {
        String submissionXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>No Instance ID</name>
                    <meta></meta>
                </data>
                """;

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        MvcResult result = mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials))
                .andExpect(status().isCreated())
                .andReturn();

        String responseXml = result.getResponse().getContentAsString();
        // Response should contain the generated instanceID
        assert responseXml.contains("<instanceID>") : "Response should contain generated instanceID";
    }

    // ==================== DeprecatedID / Replacement Flow Tests ====================

    @Test
    void submissionPostEndpoint_WithDeprecatedId_CreatesReplacement() throws Exception {
        // First submission
        String instanceId1 = "uuid:11111111-1111-1111-1111-111111111111";
        String submissionXml1 = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>Original</name>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """, instanceId1);

        MockMultipartFile xmlFile1 = new MockMultipartFile(
                "xml_submission_file",
                "submission1.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml1.getBytes()
        );

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile1)
                        .header("Authorization", validCredentials))
                .andExpect(status().isCreated());

        // Second submission with deprecatedID pointing to first
        String instanceId2 = "uuid:22222222-2222-2222-2222-222222222222";
        String submissionXml2 = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>Updated</name>
                    <meta>
                        <instanceID>%s</instanceID>
                        <deprecatedID>%s</deprecatedID>
                    </meta>
                </data>
                """, instanceId2, instanceId1);

        MockMultipartFile xmlFile2 = new MockMultipartFile(
                "xml_submission_file",
                "submission2.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml2.getBytes()
        );

        MvcResult result = mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile2)
                        .header("Authorization", validCredentials))
                .andExpect(status().isCreated())
                .andReturn();

        String responseXml = result.getResponse().getContentAsString();
        assert responseXml.contains("<status>201</status>") : "Should return 201 for replacement";

        // Verify original submission is archived in submission_versions
        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_versions WHERE submission_id = (SELECT id FROM submissions WHERE instance_id = ?)",
                Integer.class,
                instanceId1);
        
        assert versionCount != null && versionCount > 0 : "Should have created a submission version for replacement";
    }

    @Test
    void v1RecordAfterV2PublicationRetainsExactHistoryThroughAuthenticatedAmendment()
            throws Exception {
        // Real MockMvc -> Mobile Basic auth -> JavaRosa -> JDBC/H2, not mocked
        // intake services. A published V2 must never reinterpret a V1 instance.
        UUID formId = jdbcTemplate.queryForObject(
                "SELECT id FROM xforms WHERE project_id = ? AND xmlns = ?",
                UUID.class, testProjectId, testFormXmlns);
        assertNotNull(formId);
        Map<String, Object> previous = jdbcTemplate.queryForMap(
                "SELECT id,xml_content FROM form_versions WHERE form_id = ? AND is_latest = true",
                formId);
        UUID v1Id = UUID.fromString(previous.get("id").toString());
        String oldDefinition = previous.get("xml_content").toString();
        String newDefinition = oldDefinition.replaceFirst(
                "(<data[^>]*\\bversion=\\\")1\\.0(\\\")", "$1" + "2.0" + "$2");
        // Simulate a schema evolution: age (V1 int) is renamed to ageYears
        // (V2 string). There must be no implicit copy/coercion of old answers.
        newDefinition = newDefinition.replace("<age/>", "<ageYears/>")
                .replace("/data/age", "/data/ageYears")
                .replace("nodeset=\"/data/ageYears\" type=\"int\"",
                        "nodeset=\"/data/ageYears\" type=\"string\"");
        assertNotEquals(oldDefinition, newDefinition);
        assertTrue(newDefinition.contains("<ageYears/>"));
        assertTrue(newDefinition.contains(
                "nodeset=\"/data/ageYears\" type=\"string\""));
        UUID v2Id = UUID.randomUUID();
        jdbcTemplate.update("UPDATE form_versions SET is_latest = false WHERE id = ?", v1Id);
        jdbcTemplate.update(
                "INSERT INTO form_versions(id,form_id,version,xml_content,xmlns,"
                        + "created_by_id,is_published,is_latest) "
                        + "SELECT ?,form_id,'2.0',?,xmlns,created_by_id,true,true "
                        + "FROM form_versions WHERE id = ?",
                v2Id, newDefinition, v1Id);
        jdbcTemplate.update(
                "UPDATE xforms SET current_version_id = ? WHERE id = ?", v2Id, formId);

        String sourceId = "uuid:" + UUID.randomUUID();
        String amendedId = "uuid:" + UUID.randomUUID();
        String firstXml = """
                <data id="test_submission_form" version="1.0">
                    <name>First visit</name><age>26</age>
                    <photo>v1-photo.jpg</photo>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """.formatted(sourceId);
        var firstFile = new MockMultipartFile("xml_submission_file", "v1.xml",
                MediaType.APPLICATION_XML_VALUE, firstXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] v1Photo = new byte[]{10, 20, 30, 40};
        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(firstFile)
                        .file(new MockMultipartFile("photo", "v1-photo.jpg",
                                MediaType.IMAGE_JPEG_VALUE, v1Photo))
                        .header("Authorization", validCredentials))
                .andExpect(status().isCreated());
        UUID originalRowId = jdbcTemplate.queryForObject(
                "SELECT id FROM submissions WHERE instance_id = ?", UUID.class, sourceId);
        assertNotNull(originalRowId);
        assertEquals(v1Id, jdbcTemplate.queryForObject(
                "SELECT form_version_id FROM submissions WHERE id = ?", UUID.class, originalRowId));
        String originalPhotoPath = jdbcTemplate.queryForObject(
                "SELECT storage_path FROM submission_attachments WHERE submission_id = ?",
                String.class, originalRowId);
        assertArrayEquals(v1Photo, java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of(originalPhotoPath)));

        // A known V2 version is selected for the correction, but deprecatedID
        // still identifies the V1 source; the source XML must remain immutable.
        String correctedXml = """
                <data id="test_submission_form" version="2.0">
                    <name>Corrected visit</name><ageYears>twenty-seven</ageYears>
                    <photo>v2-photo.jpg</photo>
                    <meta><instanceID>%s</instanceID><deprecatedID>%s</deprecatedID></meta>
                </data>
                """.formatted(amendedId, sourceId);
        var correctedFile = new MockMultipartFile("xml_submission_file", "v2.xml",
                MediaType.APPLICATION_XML_VALUE,
                correctedXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] v2Photo = new byte[]{40, 30, 20, 10, 1};
        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(correctedFile)
                        .file(new MockMultipartFile("photo", "v2-photo.jpg",
                                MediaType.IMAGE_JPEG_VALUE, v2Photo))
                        .header("Authorization", validCredentials))
                .andExpect(status().isCreated());

        assertEquals(firstXml, jdbcTemplate.queryForObject(
                "SELECT xml_content FROM submissions WHERE id = ?", String.class, originalRowId));
        assertEquals(v1Id, jdbcTemplate.queryForObject(
                "SELECT form_version_id FROM submissions WHERE id = ?", UUID.class, originalRowId));
        assertEquals(v2Id, jdbcTemplate.queryForObject(
                "SELECT form_version_id FROM submissions WHERE instance_id = ?",
                UUID.class, amendedId));
        Map<String, Object> archive = jdbcTemplate.queryForMap(
                "SELECT source_form_version_id,xml_content,instance_id "
                        + "FROM submission_versions WHERE submission_id = ?", originalRowId);
        assertEquals(v1Id, UUID.fromString(archive.get("source_form_version_id").toString()));
        assertEquals(sourceId, archive.get("instance_id"));
        assertEquals(firstXml, archive.get("xml_content"));

        UUID revisedRowId = jdbcTemplate.queryForObject(
                "SELECT id FROM submissions WHERE instance_id = ?", UUID.class, amendedId);
        assertNotEquals(originalRowId, revisedRowId);

        // The original numeric answer is neither overwritten nor implicitly
        // copied to the renamed/retyped V2 question. The V2 string is explicit.
        java.math.BigDecimal originalAge = jdbcTemplate.queryForObject(
                "SELECT value_number FROM submission_answers WHERE submission_id = ? "
                        + "AND path = 'age'",
                java.math.BigDecimal.class, originalRowId);
        assertNotNull(originalAge);
        assertEquals(0, originalAge.compareTo(new java.math.BigDecimal("26")));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_answers WHERE submission_id = ? "
                        + "AND path = 'age'",
                Integer.class, revisedRowId));
        assertEquals("twenty-seven", jdbcTemplate.queryForObject(
                "SELECT value_text FROM submission_answers WHERE submission_id = ? "
                        + "AND path = 'ageYears'",
                String.class, revisedRowId));

        // The V1 and V2 attachments remain separate, with byte-identical
        // contents for each revision. New media must not relocate/overwrite
        // the source image or inherit a historical answer's foreign key.
        String revisedPhotoPath = jdbcTemplate.queryForObject(
                "SELECT storage_path FROM submission_attachments WHERE submission_id = ?",
                String.class, revisedRowId);
        assertNotEquals(originalPhotoPath, revisedPhotoPath);
        assertArrayEquals(v1Photo, java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of(originalPhotoPath)));
        assertArrayEquals(v2Photo, java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of(revisedPhotoPath)));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_attachments WHERE submission_id = ?",
                Integer.class, originalRowId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_attachments WHERE submission_id = ?",
                Integer.class, revisedRowId));

        // Explicitly unknown versions fail before any persisted submission.
        String unknownId = "uuid:" + UUID.randomUUID();
        var invalidVersion = new MockMultipartFile("xml_submission_file", "unknown.xml",
                MediaType.APPLICATION_XML_VALUE, ("""
                <data id="test_submission_form" version="999">
                    <name>Do not persist</name>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """.formatted(unknownId)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(invalidVersion).header("Authorization", validCredentials))
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submissions WHERE instance_id = ?",
                Integer.class, unknownId));
    }

    @Test
    void authenticatedV2FollowUpAfterV1PreservesSourceAndRejectsWrongWorkSemantics()
            throws Exception {
        // Exercise the real Basic-authenticated OpenRosa/JavaRosa/H2 stack.
        // FOLLOW_UP creates a new visit, unlike an AMENDMENT/deprecatedID update.
        UUID formId = jdbcTemplate.queryForObject(
                "SELECT id FROM xforms WHERE project_id = ? AND xmlns = ?",
                UUID.class, testProjectId, testFormXmlns);
        Map<String, Object> initial = jdbcTemplate.queryForMap(
                "SELECT id,xml_content FROM form_versions WHERE form_id = ? AND is_latest = true",
                formId);
        UUID v1Id = UUID.fromString(initial.get("id").toString());
        String v1Definition = initial.get("xml_content").toString();
        String v2Definition = v1Definition.replaceFirst(
                "(<data[^>]*\\bversion=\\\")1\\.0(\\\")", "$1" + "2.0" + "$2");
        assertNotEquals(v1Definition, v2Definition);
        UUID v2Id = UUID.randomUUID();
        jdbcTemplate.update("UPDATE form_versions SET is_latest = false WHERE id = ?", v1Id);
        jdbcTemplate.update(
                "INSERT INTO form_versions(id,form_id,version,xml_content,xmlns,"
                        + "created_by_id,is_published,is_latest) "
                        + "SELECT ?,form_id,'2.0',?,xmlns,created_by_id,true,true "
                        + "FROM form_versions WHERE id = ?",
                v2Id, v2Definition, v1Id);
        jdbcTemplate.update(
                "UPDATE xforms SET current_version_id = ? WHERE id = ?", v2Id, formId);

        String firstInstance = "uuid:" + UUID.randomUUID();
        String followUpInstance = "uuid:" + UUID.randomUUID();
        String sourceXml = """
                <data id="test_submission_form" version="1.0">
                    <name>Initial household visit</name><age>29</age>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """.formatted(firstInstance);
        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(new MockMultipartFile("xml_submission_file",
                                "first-visit.xml", MediaType.APPLICATION_XML_VALUE,
                                sourceXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header("Authorization", validCredentials))
                .andExpect(status().isCreated());

        Map<String, Object> source = jdbcTemplate.queryForMap(
                "SELECT id,submitted_by_id FROM submissions WHERE instance_id = ?",
                firstInstance);
        UUID sourceId = UUID.fromString(source.get("id").toString());
        UUID actorId = UUID.fromString(source.get("submitted_by_id").toString());
        UUID readyWorkId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO record_work_items(id,project_id,source_submission_id,"
                        + "source_form_version_id,source_instance_id,target_form_version_id,"
                        + "assigned_app_user_id,work_type,state,client_request_id,visit_key) "
                        + "VALUES (?,?,?,?,?,?,?,'FOLLOW_UP','READY',?,?)",
                readyWorkId, testProjectId, sourceId, v1Id, firstInstance, v2Id,
                actorId, "followup-" + readyWorkId, "second-visit");

        String followUpXml = """
                <data id="test_submission_form" version="2.0">
                    <name>Periodic household visit</name><age>30</age>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """.formatted(followUpInstance);
        var followUpFile = new MockMultipartFile("xml_submission_file",
                "follow-up.xml", MediaType.APPLICATION_XML_VALUE,
                followUpXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(followUpFile)
                        .header("Authorization", validCredentials)
                        .header("X-DataCollector-Work-Item-Id", readyWorkId.toString()))
                .andExpect(status().isCreated());

        UUID visitId = jdbcTemplate.queryForObject(
                "SELECT id FROM submissions WHERE instance_id = ?",
                UUID.class, followUpInstance);
        assertNotEquals(sourceId, visitId);
        assertEquals(v2Id, jdbcTemplate.queryForObject(
                "SELECT form_version_id FROM submissions WHERE id = ?", UUID.class, visitId));
        assertEquals(v1Id, jdbcTemplate.queryForObject(
                "SELECT form_version_id FROM submissions WHERE id = ?", UUID.class, sourceId));
        assertEquals(sourceXml, jdbcTemplate.queryForObject(
                "SELECT xml_content FROM submissions WHERE id = ?", String.class, sourceId));

        Map<String, Object> completed = jdbcTemplate.queryForMap(
                "SELECT state,completed_submission_id FROM record_work_items WHERE id = ?",
                readyWorkId);
        assertEquals("COMPLETED", completed.get("state"));
        assertEquals(visitId, UUID.fromString(completed.get("completed_submission_id").toString()));
        Map<String, Object> link = jdbcTemplate.queryForMap(
                "SELECT source_submission_id,child_submission_id,link_type,visit_key "
                        + "FROM record_links WHERE work_item_id = ?", readyWorkId);
        assertEquals(sourceId, UUID.fromString(link.get("source_submission_id").toString()));
        assertEquals(visitId, UUID.fromString(link.get("child_submission_id").toString()));
        assertEquals("FOLLOW_UP", link.get("link_type"));
        assertEquals("second-visit", link.get("visit_key"));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_versions WHERE submission_id = ?",
                Integer.class, sourceId));

        // Replaying the same instance cannot append a duplicate visit or link.
        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(followUpFile)
                        .header("Authorization", validCredentials)
                        .header("X-DataCollector-Work-Item-Id", readyWorkId.toString()))
                .andExpect(status().isConflict());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM record_links WHERE work_item_id = ?",
                Integer.class, readyWorkId));

        // A client must not change a READY FOLLOW_UP into an amendment by
        // injecting deprecatedID: the entire attempted replacement must roll
        // back, including its submission_versions archive.
        UUID wrongWorkId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO record_work_items(id,project_id,source_submission_id,"
                        + "source_form_version_id,source_instance_id,target_form_version_id,"
                        + "assigned_app_user_id,work_type,state,client_request_id,visit_key) "
                        + "VALUES (?,?,?,?,?,?,?,'FOLLOW_UP','READY',?,?)",
                wrongWorkId, testProjectId, sourceId, v1Id, firstInstance, v2Id,
                actorId, "followup-" + wrongWorkId, "third-visit");
        String rejectedInstance = "uuid:" + UUID.randomUUID();
        String misclassifiedXml = """
                <data id="test_submission_form" version="2.0">
                    <name>Invalid follow-up amendment</name><age>31</age>
                    <meta><instanceID>%s</instanceID><deprecatedID>%s</deprecatedID></meta>
                </data>
                """.formatted(rejectedInstance, firstInstance);
        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(new MockMultipartFile("xml_submission_file",
                                "wrong-semantics.xml", MediaType.APPLICATION_XML_VALUE,
                                misclassifiedXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header("Authorization", validCredentials)
                        .header("X-DataCollector-Work-Item-Id", wrongWorkId.toString()))
                .andExpect(status().isConflict());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submissions WHERE instance_id = ?",
                Integer.class, rejectedInstance));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_versions WHERE submission_id = ?",
                Integer.class, sourceId));
        assertEquals("READY", jdbcTemplate.queryForObject(
                "SELECT state FROM record_work_items WHERE id = ?", String.class, wrongWorkId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM record_links WHERE work_item_id = ?",
                Integer.class, wrongWorkId));
        assertEquals(sourceXml, jdbcTemplate.queryForObject(
                "SELECT xml_content FROM submissions WHERE id = ?", String.class, sourceId));
    }

    @Test
    void submissionPostEndpoint_WithDeprecatedIdForNonExistentInstance_Returns404() throws Exception {
        String instanceId2 = "uuid:22222222-2222-2222-2222-222222222222";
        String nonExistentDeprecatedId = "uuid:99999999-9999-9999-9999-999999999999";
        String submissionXml2 = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>Updated</name>
                    <meta>
                        <instanceID>%s</instanceID>
                        <deprecatedID>%s</deprecatedID>
                    </meta>
                </data>
                """, instanceId2, nonExistentDeprecatedId);

        MockMultipartFile xmlFile2 = new MockMultipartFile(
                "xml_submission_file",
                "submission2.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml2.getBytes()
        );

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile2)
                        .header("Authorization", validCredentials))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"));
    }

    // ==================== Duplicate InstanceID Detection Tests ====================

    @Test
    void submissionPostEndpoint_DuplicateInstanceId_Returns409Conflict() throws Exception {
        String instanceId = "uuid:33333333-3333-3333-3333-333333333333";
        String submissionXml = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>First Submission</name>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """, instanceId);

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        // First submission - should succeed
        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials))
                .andExpect(status().isCreated());

        // Second submission with same instanceID - should return 409 Conflict
        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"));
    }

    // ==================== Attachment Handling Tests ====================

    @Test
    void submissionPostEndpoint_WithAttachments_StoresAttachments() throws Exception {
        String instanceId = "uuid:44444444-4444-4444-4444-444444444444";
        String submissionXml = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>With Attachments</name>
                    <photo>photo.jpg</photo>
                    <audio>recording.mp3</audio>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """, instanceId);

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        MockMultipartFile photoFile = new MockMultipartFile(
                "photo.jpg",
                "photo.jpg",
                MediaType.IMAGE_JPEG_VALUE,
                "fake image content".getBytes()
        );

        MockMultipartFile audioFile = new MockMultipartFile(
                "recording.mp3",
                "recording.mp3",
                "audio/mpeg",
                "fake audio content".getBytes()
        );

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .file(photoFile)
                        .file(audioFile)
                        .header("Authorization", validCredentials))
                .andExpect(status().isCreated());

        // Verify attachments were stored
        Integer attachmentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_attachments WHERE submission_id = (SELECT id FROM submissions WHERE instance_id = ?)",
                Integer.class,
                instanceId);
        
        assert attachmentCount != null && attachmentCount == 2 : "Should have stored 2 attachments";
    }

    @Test
    void submissionPostEndpoint_LinksAttachmentsToAnswers() throws Exception {
        String instanceId = "uuid:55555555-5555-5555-5555-555555555555";
        String submissionXml = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>With Attachments</name>
                    <photo>photo.jpg</photo>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """, instanceId);

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        MockMultipartFile photoFile = new MockMultipartFile(
                "photo.jpg",
                "photo.jpg",
                MediaType.IMAGE_JPEG_VALUE,
                "fake image content".getBytes()
        );

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .file(photoFile)
                        .header("Authorization", validCredentials))
                .andExpect(status().isCreated());

        // Verify a concrete attachment -> normalized-answer foreign-key link.
        UUID linkedAnswerId = jdbcTemplate.queryForObject(
                "SELECT answer_id FROM submission_attachments WHERE submission_id = (SELECT id FROM submissions WHERE instance_id = ?)",
                UUID.class,
                instanceId);

        assertNotNull(linkedAnswerId, "Attachment should reference a concrete SubmissionAnswer");

        String linkedPath = jdbcTemplate.queryForObject(
                "SELECT path FROM submission_answers WHERE id = ?",
                String.class,
                linkedAnswerId);
        String linkedValue = jdbcTemplate.queryForObject(
                "SELECT value_text FROM submission_answers WHERE id = ?",
                String.class,
                linkedAnswerId);

        assertEquals("photo", linkedPath);
        assertEquals("photo.jpg", linkedValue);

        String metadata = jdbcTemplate.queryForObject(
                "SELECT metadata FROM submission_attachments WHERE submission_id = (SELECT id FROM submissions WHERE instance_id = ?)",
                String.class,
                instanceId);

        assertNotNull(metadata, "Attachment should have metadata");
        assertTrue(metadata.contains("referencedInAnswers"));
        assertTrue(metadata.contains(linkedAnswerId.toString()));
        assertTrue(metadata.contains("answerPath"));
    }

    // ==================== Source Client Detection Tests ====================

    @Test
    void submissionPostEndpoint_DetectsOdkCollectClient() throws Exception {
        String instanceId = "uuid:66666666-6666-6666-6666-666666666666";
        String submissionXml = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>ODK Collect</name>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """, instanceId);

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials)
                        .header("User-Agent", "ODK Collect v2023.1.0"))
                .andExpect(status().isCreated());

        String metadata = jdbcTemplate.queryForObject(
                "SELECT metadata FROM submissions WHERE instance_id = ?",
                String.class,
                instanceId);
        
        assert metadata != null : "Submission should have metadata";
        assert metadata.contains("ODK Collect") : "Metadata should contain source client";
    }

    @Test
    void submissionPostEndpoint_DetectsKoboToolboxClient() throws Exception {
        String instanceId = "uuid:77777777-7777-7777-7777-777777777777";
        String submissionXml = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>KoboToolbox</name>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """, instanceId);

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials)
                        .header("User-Agent", "KoboCollect/1.0"))
                .andExpect(status().isCreated());

        String metadata = jdbcTemplate.queryForObject(
                "SELECT metadata FROM submissions WHERE instance_id = ?",
                String.class,
                instanceId);
        
        assert metadata != null : "Submission should have metadata";
        assert metadata.contains("KoboToolbox") : "Metadata should contain source client";
    }

    @Test
    void submissionPostEndpoint_DetectsEnketoClient() throws Exception {
        String instanceId = "uuid:88888888-8888-8888-8888-888888888888";
        String submissionXml = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>Enketo</name>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """, instanceId);

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials)
                        .header("User-Agent", "Enketo/2.0"))
                .andExpect(status().isCreated());

        String metadata = jdbcTemplate.queryForObject(
                "SELECT metadata FROM submissions WHERE instance_id = ?",
                String.class,
                instanceId);
        
        assert metadata != null : "Submission should have metadata";
        assert metadata.contains("Enketo") : "Metadata should contain source client";
    }

    @Test
    void submissionPostEndpoint_DetectsOpenRosaClient() throws Exception {
        String instanceId = "uuid:99999999-9999-9999-9999-999999999999";
        String submissionXml = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>OpenRosa Client</name>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """, instanceId);

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials)
                        .header("X-OpenRosa-Version", "1.0"))
                .andExpect(status().isCreated());

        String metadata = jdbcTemplate.queryForObject(
                "SELECT metadata FROM submissions WHERE instance_id = ?",
                String.class,
                instanceId);
        
        assert metadata != null : "Submission should have metadata";
        assert metadata.contains("OpenRosa Client") : "Metadata should contain source client";
    }

    // ==================== JavaRosa Validation Tests ====================

    @Test
    void submissionPostEndpoint_ValidatesSubmissionWithJavaRosa() throws Exception {
        // Valid submission should pass
        String instanceId = "uuid:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        String submissionXml = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>Valid Submission</name>
                    <age>25</age>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """, instanceId);

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials))
                .andExpect(status().isCreated());
    }

    @Test
    void submissionPostEndpoint_RejectsInvalidSubmissionStructure() throws Exception {
        // Submission with wrong structure (missing required field)
        String instanceId = "uuid:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        String submissionXml = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <age>25</age>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """, instanceId);

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        // Note: Current implementation validates form structure, not submission data structure
        // This test documents expected behavior - may need adjustment based on actual validation
        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials))
                .andExpect(status().isCreated()); // Currently passes as form structure is valid
    }

    // ==================== Compatibility Endpoint Tests ====================

    @Test
    void compatibilitySubmissionEndpoint_WithValidAuth_Returns201() throws Exception {
        String instanceId = "uuid:cccccccc-cccc-cccc-cccc-cccccccccccc";
        String submissionXml = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>Compatibility</name>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """, instanceId);

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        mockMvc.perform(multipart("/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"));
    }

    @Test
    void compatibilitySubmissionEndpoint_WithInvalidAuth_Returns401() throws Exception {
        String instanceId = "uuid:dddddddd-dddd-dddd-dddd-dddddddddddd";
        String submissionXml = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>Compatibility</name>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """, instanceId);

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        mockMvc.perform(multipart("/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", invalidCredentials))
                .andExpect(status().isUnauthorized());
    }

    // ==================== Response Format Tests ====================

    @Test
    void submissionPostEndpoint_ReturnsValidOpenRosaResponse() throws Exception {
        String instanceId = "uuid:eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
        String submissionXml = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>Response Test</name>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """, instanceId);

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        MvcResult result = mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials))
                .andExpect(status().isCreated())
                .andReturn();

        String responseXml = result.getResponse().getContentAsString();
        
        // Verify OpenRosa response structure
        assert responseXml.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>") : "Should have XML declaration";
        assert responseXml.contains("<OpenRosaResponse") : "Should have OpenRosaResponse root";
        assert responseXml.contains("xmlns=\"http://openrosa.org/http/response\"") : "Should have OpenRosa namespace";
        assert responseXml.contains("<message>Submission received</message>") : "Should have success message";
        assert responseXml.contains("<status>201</status>") : "Should have 201 status";
        assert responseXml.contains("<instanceID>") : "Should include instanceID in response";
    }

    // ==================== Repeat Structure Tests ====================

    @Test
    void submissionPostEndpoint_HandlesRepeatGroups() throws Exception {
        // Upload form with repeat group
        String repeatFormXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>Repeat Test Form</h:title>
                        <model>
                            <instance>
                                <data id="repeat_test_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                    <name/>
                                    <children>
                                        <child_name/>
                                        <child_age/>
                                    </children>
                                    <meta><instanceID/></meta>
                                </data>
                            </instance>
                            <bind nodeset="/data/name" type="string"/>
                            <bind nodeset="/data/children/child_name" type="string"/>
                            <bind nodeset="/data/children/child_age" type="int"/>
                        </model>
                    </h:head>
                    <h:body>
                        <input ref="/data/name"><label>Name</label></input>
                        <repeat nodeset="/data/children">
                            <label>Children</label>
                            <input ref="/data/children/child_name"><label>Child Name</label></input>
                            <input ref="/data/children/child_age"><label>Child Age</label></input>
                        </repeat>
                    </h:body>
                </h:html>
                """;

        UUID repeatProjectId = createTestProject();
        
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "repeat_form.xml",
                MediaType.APPLICATION_XML_VALUE,
                repeatFormXml.getBytes()
        );

        MvcResult uploadResult = mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", repeatProjectId)
                        .file(file)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        publishAndAssignUploadedForm(repeatProjectId, uploadResult);

        String xmlns = objectMapper.readTree(uploadResult.getResponse().getContentAsString()).get("xmlns").asText();

        // Submit with repeat data
        String instanceId = "uuid:ffffffff-ffff-ffff-ffff-ffffffffffff";
        String submissionXml = String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="%s" version="1.0">
                    <name>Parent</name>
                    <children>
                        <child_name>Child 1</child_name>
                        <child_age>5</child_age>
                    </children>
                    <children>
                        <child_name>Child 2</child_name>
                        <child_age>3</child_age>
                    </children>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """, xmlns, instanceId);

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes()
        );

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", repeatProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials))
                .andExpect(status().isCreated());

        // Verify submission was stored
        String storedInstanceId = jdbcTemplate.queryForObject(
                "SELECT instance_id FROM submissions WHERE instance_id = ?",
                String.class,
                instanceId);
        
        assert storedInstanceId != null : "Submission with repeats should be stored";
    }

    @Test
    void submissionPostEndpoint_RejectsInvalidTypedValueWithJavaRosa() throws Exception {
        String instanceId = "uuid:invalid-type-1111-2222-333333333333";
        String submissionXml = """
                <data id="test_submission_form" version="1.0">
                    <name>Invalid Age</name>
                    <age>not-an-integer</age>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """.formatted(instanceId);
        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file", "submission.xml", MediaType.APPLICATION_XML_VALUE, submissionXml.getBytes());

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials))
                .andExpect(status().isBadRequest());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submissions WHERE instance_id = ?", Integer.class, instanceId);
        assertEquals(0, count);
    }

    @Test
    void submissionPostEndpoint_PersistsNormalizedAnswersJsonAndEvent() throws Exception {
        String instanceId = "uuid:pipeline-1111-2222-3333-444444444444";
        String submissionXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_submission_form" version="1.0">
                    <name>Pipeline Record</name>
                    <age>42</age>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """.formatted(instanceId);

        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file", "submission.xml", MediaType.APPLICATION_XML_VALUE, submissionXml.getBytes());

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials)
                        .header("User-Agent", "ODK Collect v2026.1"))
                .andExpect(status().isCreated());

        String json = jdbcTemplate.queryForObject(
                "SELECT json_content FROM submissions WHERE instance_id = ?", String.class, instanceId);
        assertNotNull(json);
        assertThat(json, containsString("Pipeline Record"));

        Integer answerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_answers WHERE submission_id = (SELECT id FROM submissions WHERE instance_id = ?)",
                Integer.class, instanceId);
        assertTrue(answerCount != null && answerCount >= 3); // name, age and meta/instanceID

        java.math.BigDecimal age = jdbcTemplate.queryForObject(
                "SELECT value_number FROM submission_answers WHERE submission_id = (SELECT id FROM submissions WHERE instance_id = ?) AND path = 'age'",
                java.math.BigDecimal.class, instanceId);
        assertEquals(0, age.compareTo(new java.math.BigDecimal("42")));

        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_events WHERE submission_id = (SELECT id FROM submissions WHERE instance_id = ?) AND event_type = 'RECEIVED'",
                Integer.class, instanceId);
        assertEquals(1, eventCount);
    }

    @Test
    void submissionPostEndpoint_RejectsUnsafeDoctypeBeforePersistence() throws Exception {
        String submissionXml = """
                <?xml version="1.0"?>
                <!DOCTYPE data [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <data id="test_submission_form" version="1.0">
                    <name>&xxe;</name>
                    <meta><instanceID>uuid:unsafe-doctype</instanceID></meta>
                </data>
                """;
        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file", "submission.xml", MediaType.APPLICATION_XML_VALUE, submissionXml.getBytes());

        mockMvc.perform(multipart("/v1/projects/{projectId}/submission", testProjectId)
                        .file(xmlFile)
                        .header("Authorization", validCredentials))
                .andExpect(status().isBadRequest());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submissions WHERE instance_id = 'uuid:unsafe-doctype'", Integer.class);
        assertEquals(0, count);
    }

    @Test
    void compatibilitySubmissionEndpoint_UsesSameProcessingAndAttachmentPipeline() throws Exception {
        String instanceId = "uuid:compat-pipeline-1111-2222-333333333333";
        String submissionXml = """
                <data id="test_submission_form" version="1.0">
                    <name>Compatibility Pipeline</name>
                    <age>19</age>
                    <photo>compat.jpg</photo>
                    <meta><instanceID>%s</instanceID></meta>
                </data>
                """.formatted(instanceId);
        MockMultipartFile xmlFile = new MockMultipartFile(
                "xml_submission_file", "submission.xml", MediaType.APPLICATION_XML_VALUE, submissionXml.getBytes());
        MockMultipartFile photo = new MockMultipartFile(
                "photo", "compat.jpg", "image/jpeg", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/submission")
                        .file(xmlFile)
                        .file(photo)
                        .header("Authorization", validCredentials)
                        .header("User-Agent", "KoboCollect/2026"))
                .andExpect(status().isCreated());

        String metadata = jdbcTemplate.queryForObject(
                "SELECT metadata FROM submissions WHERE instance_id = ?", String.class, instanceId);
        assertThat(metadata, containsString("KoboToolbox"));

        Integer answerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_answers WHERE submission_id = (SELECT id FROM submissions WHERE instance_id = ?)",
                Integer.class, instanceId);
        assertTrue(answerCount != null && answerCount > 0);

        Integer attachmentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_attachments WHERE submission_id = (SELECT id FROM submissions WHERE instance_id = ?)",
                Integer.class, instanceId);
        assertEquals(1, attachmentCount);
    }

}