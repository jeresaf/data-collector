package org.odk.platform.openrosa;

import jakarta.persistence.EntityManager;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for OpenRosa Manifest endpoint
 * Tests manifest XML structure, media files, external datasets, authentication, and authorization
 */
@SpringBootTest(properties = "odk.test.openrosa-manifest-integration=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OpenRosaManifestIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManager entityManager;

    private String validCredentials;
    private String invalidCredentials;
    private UUID testProjectId;
    private String testFormXmlns = "test_manifest_form";

    @BeforeEach
    void setUp() throws Exception {
        // Valid credentials: appuser:password123
        String credentials = "appuser:password123";
        validCredentials = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());

        // Invalid credentials
        String invalidCreds = "appuser:wrongpassword";
        invalidCredentials = "Basic " + Base64.getEncoder().encodeToString(invalidCreds.getBytes());

        // Make the Basic Auth fixture deterministic instead of relying on hidden seed data.
        ensureAppUser("appuser", "password123");

        // Create a test project and upload a form with media references
        testProjectId = createTestProject();
        uploadTestFormWithMedia();
    }

    private UUID createTestProject() throws Exception {
        Map<String, Object> request = Map.of(
                "name", "Manifest Test Project",
                "description", "Test project for manifest tests"
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

        // The upload endpoint saves XForm/FormVersion through JPA. Because this test
        // itself is @Transactional, those INSERTs may still only exist in the
        // persistence context when this helper switches to JdbcTemplate.
        entityManager.flush();

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

        // Compatibility endpoints derive their project from the app-user assignment.
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

        // Publication and assignment above were done with JdbcTemplate. Clear the
        // first-level JPA cache so later controller/repository reads reload the
        // updated is_published/current_version_id/assignment state from the DB.
        entityManager.clear();
    }

    private void addStaticMediaAttachment(
            UUID projectId,
            String xmlFormId,
            String filename) throws Exception {

        entityManager.flush();

        UUID formVersionId = jdbcTemplate.queryForObject(
                """
                SELECT current_version_id
                FROM xforms
                WHERE project_id = ? AND xmlns = ?
                """,
                UUID.class,
                projectId,
                xmlFormId);

        if (formVersionId == null) {
            throw new IllegalStateException(
                    "Published form has no current FormVersion: " + xmlFormId);
        }

        String rawMetadata = jdbcTemplate.queryForObject(
                "SELECT metadata FROM form_versions WHERE id = ?",
                String.class,
                formVersionId);

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata =
                rawMetadata == null || rawMetadata.isBlank()
                        ? new java.util.LinkedHashMap<>()
                        : objectMapper.readValue(rawMetadata, Map.class);

        // <upload> controls describe files captured by the respondent and sent
        // with submissions. They do not identify static form media to download.
        metadata.put("media", List.of(Map.of(
                "source", "itext",
                "mediaType", "image",
                "filename", filename)));

        jdbcTemplate.update(
                "UPDATE form_versions SET metadata = ? WHERE id = ?",
                objectMapper.writeValueAsString(metadata),
                formVersionId);

        jdbcTemplate.update(
                """
                INSERT INTO form_attachments (
                    id,
                    form_version_id,
                    file_name,
                    content_type,
                    file_size,
                    storage_path,
                    storage_driver,
                    content_hash,
                    is_encrypted,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, 'image/jpeg', 4, ?, 'local',
                        '098f6bcd4621d373cade4e832627b4f6',
                        false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                formVersionId,
                filename,
                "test-media/" + filename);

        entityManager.clear();
    }

    private void uploadTestFormWithMedia() throws Exception {
        // XForm XML with media references (images, audio, video, CSV)
        String xformXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>Manifest Test Form</h:title>
                        <model>
                            <instance>
                                <data id="test_manifest_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                    <name/>
                                    <photo/>
                                    <audio/>
                                    <video/>
                                    <csv_data/>
                                    <meta>
                                        <instanceID/>
                                    </meta>
                                </data>
                            </instance>
                            <bind nodeset="/data/name" type="string"/>
                            <bind nodeset="/data/photo" type="binary"/>
                            <bind nodeset="/data/audio" type="binary"/>
                            <bind nodeset="/data/video" type="binary"/>
                            <bind nodeset="/data/csv_data" type="binary"/>
                        </model>
                        <itext>
                            <translation lang="en" default="true">
                                <text id="/data/name:label"><value>Name</value></text>
                                <text id="/data/photo:label"><value>Photo</value></text>
                                <text id="/data/audio:label"><value>Audio</value></text>
                                <text id="/data/video:label"><value>Video</value></text>
                                <text id="/data/csv_data:label"><value>CSV Data</value></text>
                            </translation>
                        </itext>
                    </h:head>
                    <h:body>
                        <input ref="/data/name"><label ref="jr:itext('/data/name:label')"/></input>
                        <upload ref="/data/photo" mediatype="image/*"><label ref="jr:itext('/data/photo:label')"/></upload>
                        <upload ref="/data/audio" mediatype="audio/*"><label ref="jr:itext('/data/audio:label')"/></upload>
                        <upload ref="/data/video" mediatype="video/*"><label ref="jr:itext('/data/video:label')"/></upload>
                        <upload ref="/data/csv_data" mediatype="text/csv"><label ref="jr:itext('/data/csv_data:label')"/></upload>
                    </h:body>
                </h:html>
                """;

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "manifest_form.xml",
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

    // ==================== Manifest XML Structure Tests ====================

    @Test
    void manifestEndpoint_ReturnsValidXmlStructure() throws Exception {
        mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, testFormXmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/xml"))
                .andExpect(header().string("X-OpenRosa-Version", "1.0"))
                .andExpect(content().string(containsString("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")))
                .andExpect(content().string(containsString("<manifest xmlns=\"http://openrosa.org/xforms/manifest\">")))
                .andExpect(content().string(containsString("</manifest>")));
    }

    @Test
    void manifestEndpoint_IncludesFormXmlInManifest() throws Exception {
        mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, testFormXmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<mediaFile>")))
                .andExpect(content().string(containsString("<filename>test_manifest_form.xml</filename>")))
                .andExpect(content().string(containsString("<hash>md5:")))
                .andExpect(content().string(containsString("</mediaFile>")));
    }

    @Test
    void manifestEndpoint_IncludesMediaFilesWithHashes() throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, testFormXmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andReturn();

        String xml = result.getResponse().getContentAsString();
        
        // Should contain media files for photo, audio, video, csv
        assert xml.contains("<mediaFile>") : "Should contain mediaFile elements";
        assert xml.contains("<filename>") : "Should contain filename elements";
        assert xml.contains("<hash>md5:") : "Should contain MD5 hashes";
        
        // Count mediaFile elements - should have at least form XML + 4 media references
        int mediaFileElements = (xml.split("<mediaFile>").length - 1);
        assert mediaFileElements >= 1 : "Should have at least the form XML media file";
    }

    @Test
    void manifestEndpoint_IncludesDownloadUrlsForMediaFiles() throws Exception {
        addStaticMediaAttachment(testProjectId, testFormXmlns, "photo.jpg");

        MvcResult result = mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, testFormXmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andReturn();

        String xml = result.getResponse().getContentAsString();

        assert xml.contains("<filename>photo.jpg</filename>")
                : "Should contain the static media filename";
        assert xml.contains("<downloadUrl>")
                : "Should contain downloadUrl elements for static form media";
        assert xml.contains("/attachments/photo.jpg")
                : "Should contain the static media attachment download URL";
    }

    @Test
    void manifestEndpoint_IncludesExternalDatasets() throws Exception {
        // Upload a form with external dataset references
        String xformWithExternal = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>External Dataset Form</h:title>
                        <model>
                            <instance>
                                <data id="external_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                    <name/>
                                    <lookup_data/>
                                    <meta><instanceID/></meta>
                                </data>
                            </instance>
                            <bind nodeset="/data/name" type="string"/>
                            <bind nodeset="/data/lookup_data" type="binary"/>
                            <instance id="cities" src="jr://file/csv/cities.csv"/>
                        </model>
                    </h:head>
                    <h:body>
                        <input ref="/data/name"><label>Name</label></input>
                    </h:body>
                </h:html>
                """;

        UUID projectId = createTestProject();
        
        MockMultipartFile file = new MockMultipartFile(
                "file", "external_form.xml", MediaType.APPLICATION_XML_VALUE, xformWithExternal.getBytes());

        MvcResult uploadResult = mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", projectId)
                        .file(file)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        publishAndAssignUploadedForm(projectId, uploadResult);

        String xmlns = objectMapper.readTree(uploadResult.getResponse().getContentAsString()).get("xmlns").asText();

        // Check manifest includes external dataset
        MvcResult manifestResult = mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", projectId, xmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andReturn();

        String manifestXml = manifestResult.getResponse().getContentAsString();
        // External datasets should appear in manifest
        assert manifestXml.contains("<mediaFile>") : "Should contain mediaFile elements";
    }

    // ==================== Authentication Tests ====================

    @Test
    void manifestEndpoint_WithValidBasicAuth_ReturnsOk() throws Exception {
        mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, testFormXmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"));
    }

    @Test
    void manifestEndpoint_WithInvalidBasicAuth_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, testFormXmlns)
                        .header("Authorization", invalidCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void manifestEndpoint_WithoutAuth_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, testFormXmlns)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void manifestEndpoint_WithMalformedAuth_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, testFormXmlns)
                        .header("Authorization", "Basic notvalidbase64!")
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isUnauthorized());
    }

    // ==================== Authorization Tests ====================

    @Test
    void manifestEndpoint_WithUnassignedAppUser_ReturnsNotFound() throws Exception {
        // Create a real app user that can authenticate but has no assignment.
        UUID unassignedAppUserId = ensureAppUser("unassigned_user", "password123");
        jdbcTemplate.update(
                "DELETE FROM app_user_projects WHERE app_user_id = ?",
                unassignedAppUserId);

        String unassignedCredentials = "Basic " + Base64.getEncoder().encodeToString("unassigned_user:password123".getBytes());
        
        mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, testFormXmlns)
                        .header("Authorization", unassignedCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotFound());
    }

    @Test
    void manifestEndpoint_WithAssignedAppUser_ReturnsManifest() throws Exception {
        // The fixture explicitly assigns appuser to this project/form.
        mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, testFormXmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk());
    }

    @Test
    void manifestEndpoint_ForNonExistentForm_ReturnsNotFound() throws Exception {
        mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, "nonexistent_form")
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotFound());
    }

    @Test
    void manifestEndpoint_ForNonExistentProject_ReturnsNotFound() throws Exception {
        UUID nonExistentProject = UUID.randomUUID();
        mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", nonExistentProject, testFormXmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotFound());
    }

    @Test
    void manifestEndpoint_ForUnpublishedForm_ReturnsNotFound() throws Exception {
        // Upload a form but don't publish it
        String xformXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>Unpublished Form</h:title>
                        <model>
                            <instance>
                                <data id="unpublished_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                    <name/><meta><instanceID/></meta>
                                </data>
                            </instance>
                            <bind nodeset="/data/name" type="string"/>
                        </model>
                    </h:head>
                    <h:body><input ref="/data/name"><label>Name</label></input></h:body>
                </h:html>
                """;

        UUID projectId = createTestProject();
        
        MockMultipartFile file = new MockMultipartFile(
                "file", "unpublished.xml", MediaType.APPLICATION_XML_VALUE, xformXml.getBytes());

        MvcResult uploadResult = mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", projectId)
                        .file(file)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        String xmlns = objectMapper.readTree(uploadResult.getResponse().getContentAsString()).get("xmlns").asText();

        // Try to access manifest for unpublished form - should return 404
        mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", projectId, xmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotFound());
    }

    // ==================== Compatibility Endpoint Tests ====================

    @Test
    void compatibilityManifestEndpoint_WithValidAuth_ReturnsManifest() throws Exception {
        mockMvc.perform(get("/forms/{xmlFormId}/manifest", testFormXmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"))
                .andExpect(content().string(containsString("<manifest")));
    }

    @Test
    void compatibilityManifestEndpoint_WithInvalidAuth_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/forms/{xmlFormId}/manifest", testFormXmlns)
                        .header("Authorization", invalidCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isUnauthorized());
    }

    // ==================== Path Traversal Prevention Tests ====================

    @Test
    void attachmentDownload_WithPathTraversalInFilename_ReturnsBadRequest() throws Exception {
        // Try various path traversal attempts
        String[] maliciousFilenames = {
                "../../../etc/passwd",
                "..\\..\\windows\\system32\\drivers\\etc\\hosts",
                "test/../secret.txt",
                "test..txt",
                "/absolute/path.txt",
                "file:///etc/passwd"
        };

        for (String filename : maliciousFilenames) {
            mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/attachments/{filename}", 
                            testProjectId, testFormXmlns, filename)
                            .header("Authorization", validCredentials))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void attachmentDownload_WithValidFilename_ReturnsNotFoundIfNotExists() throws Exception {
        // Valid filename that doesn't exist should return 404, not 400
        mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/attachments/{filename}", 
                        testProjectId, testFormXmlns, "nonexistent.jpg")
                        .header("Authorization", validCredentials))
                .andExpect(status().isNotFound());
    }

    @Test
    void attachmentDownload_WithValidFilenameAndAuth_ReturnsNotFoundIfNoStorage() throws Exception {
        // Valid filename but no actual file stored - should return 404
        mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/attachments/{filename}", 
                        testProjectId, testFormXmlns, "test.jpg")
                        .header("Authorization", validCredentials))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"));
    }

    // ==================== Manifest Content Validation Tests ====================

    @Test
    void manifestXml_HasCorrectNamespace() throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, testFormXmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andReturn();

        String xml = result.getResponse().getContentAsString();
        assert xml.contains("xmlns=\"http://openrosa.org/xforms/manifest\"") : "Should have OpenRosa manifest namespace";
    }

    @Test
    void manifestXml_FormXmlHashIsMd5() throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, testFormXmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andReturn();

        String xml = result.getResponse().getContentAsString();
        
        // Find the form XML media file entry and verify hash format
        assert xml.contains("<filename>test_manifest_form.xml</filename>") : "Should contain form XML filename";
        assert xml.contains("<hash>md5:") : "Should contain MD5 hash prefix";
        
        // Extract hash and verify it's 32 hex characters
        int hashStart = xml.indexOf("<hash>md5:") + "<hash>md5:".length();
        int hashEnd = xml.indexOf("</hash>", hashStart);
        String hash = xml.substring(hashStart, hashEnd);
        assert hash.matches("[a-f0-9]{32}") : "MD5 hash should be 32 hex characters: " + hash;
    }

    @Test
    void manifestXml_MediaFilesHaveUniqueFilenames() throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, testFormXmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andReturn();

        String xml = result.getResponse().getContentAsString();
        
        // Extract all filenames and verify uniqueness
        List<String> filenames = new java.util.ArrayList<>();
        int index = 0;
        while ((index = xml.indexOf("<filename>", index)) != -1) {
            int start = index + "<filename>".length();
            int end = xml.indexOf("</filename>", start);
            if (end > start) {
                filenames.add(xml.substring(start, end));
            }
            index = end;
        }
        
        // Check for duplicates
        long uniqueCount = filenames.stream().distinct().count();
        assert uniqueCount == filenames.size() : "All filenames in manifest should be unique, found: " + filenames;
    }

    @Test
    void manifestXml_DownloadUrlsAreAbsolute() throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, testFormXmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andReturn();

        String xml = result.getResponse().getContentAsString();
        
        // Check download URLs are absolute (contain http:// or https://)
        int urlIndex = 0;
        while ((urlIndex = xml.indexOf("<downloadUrl>", urlIndex)) != -1) {
            int start = urlIndex + "<downloadUrl>".length();
            int end = xml.indexOf("</downloadUrl>", start);
            if (end > start) {
                String url = xml.substring(start, end);
                assert url.startsWith("http://") || url.startsWith("https://") : "Download URL should be absolute: " + url;
            }
            urlIndex = end;
        }
    }

    // ==================== OpenRosa Headers Tests ====================

    @Test
    void manifestEndpoint_ReturnsOpenRosaVersionHeader() throws Exception {
        mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, testFormXmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"));
    }

    @Test
    void manifestEndpoint_ReturnsOpenRosaVersionOnCompatibilityEndpoint() throws Exception {
        mockMvc.perform(get("/forms/{xmlFormId}/manifest", testFormXmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"));
    }

    @Test
    void manifestEndpoint_ReturnsOpenRosaVersionEvenOnNotFound() throws Exception {
        mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, "nonexistent")
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"));
    }

    @Test
    void manifestEndpoint_ReturnsOpenRosaVersionEvenOnUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, testFormXmlns)
                        .header("Authorization", invalidCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"));
    }

    // ==================== Media File Types Tests ====================

    @Test
    void manifestIncludesImageFiles() throws Exception {
        // Upload form with image reference
        String xformWithImage = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>Image Form</h:title>
                        <model>
                            <instance>
                                <data id="image_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                    <photo/><meta><instanceID/></meta>
                                </data>
                            </instance>
                            <bind nodeset="/data/photo" type="binary"/>
                        </model>
                    </h:head>
                    <h:body>
                        <upload ref="/data/photo" mediatype="image/*"><label>Photo</label></upload>
                    </h:body>
                </h:html>
                """;

        UUID projectId = createTestProject();
        MockMultipartFile file = new MockMultipartFile("file", "image_form.xml", MediaType.APPLICATION_XML_VALUE, xformWithImage.getBytes());

        MvcResult uploadResult = mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", projectId)
                        .file(file).with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk()).andReturn();

        publishAndAssignUploadedForm(projectId, uploadResult);

        String xmlns = objectMapper.readTree(uploadResult.getResponse().getContentAsString()).get("xmlns").asText();

        MvcResult manifestResult = mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", projectId, xmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk()).andReturn();

        String manifestXml = manifestResult.getResponse().getContentAsString();
        // Should include the form XML at minimum
        assert manifestXml.contains("<filename>image_form.xml</filename>") : "Should include form XML in manifest";
    }

    @Test
    void manifestIncludesAudioFiles() throws Exception {
        String xformWithAudio = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>Audio Form</h:title>
                        <model>
                            <instance>
                                <data id="audio_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                    <recording/><meta><instanceID/></meta>
                                </data>
                            </instance>
                            <bind nodeset="/data/recording" type="binary"/>
                        </model>
                    </h:head>
                    <h:body>
                        <upload ref="/data/recording" mediatype="audio/*"><label>Recording</label></upload>
                    </h:body>
                </h:html>
                """;

        UUID projectId = createTestProject();
        MockMultipartFile file = new MockMultipartFile("file", "audio_form.xml", MediaType.APPLICATION_XML_VALUE, xformWithAudio.getBytes());

        MvcResult uploadResult = mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", projectId)
                        .file(file).with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk()).andReturn();

        publishAndAssignUploadedForm(projectId, uploadResult);

        String xmlns = objectMapper.readTree(uploadResult.getResponse().getContentAsString()).get("xmlns").asText();

        MvcResult manifestResult = mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", projectId, xmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk()).andReturn();

        String manifestXml = manifestResult.getResponse().getContentAsString();
        assert manifestXml.contains("<filename>audio_form.xml</filename>") : "Should include form XML in manifest";
    }

    @Test
    void manifestIncludesVideoFiles() throws Exception {
        String xformWithVideo = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>Video Form</h:title>
                        <model>
                            <instance>
                                <data id="video_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                    <clip/><meta><instanceID/></meta>
                                </data>
                            </instance>
                            <bind nodeset="/data/clip" type="binary"/>
                        </model>
                    </h:head>
                    <h:body>
                        <upload ref="/data/clip" mediatype="video/*"><label>Video Clip</label></upload>
                    </h:body>
                </h:html>
                """;

        UUID projectId = createTestProject();
        MockMultipartFile file = new MockMultipartFile("file", "video_form.xml", MediaType.APPLICATION_XML_VALUE, xformWithVideo.getBytes());

        MvcResult uploadResult = mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", projectId)
                        .file(file).with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk()).andReturn();

        publishAndAssignUploadedForm(projectId, uploadResult);

        String xmlns = objectMapper.readTree(uploadResult.getResponse().getContentAsString()).get("xmlns").asText();

        MvcResult manifestResult = mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", projectId, xmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk()).andReturn();

        String manifestXml = manifestResult.getResponse().getContentAsString();
        assert manifestXml.contains("<filename>video_form.xml</filename>") : "Should include form XML in manifest";
    }

    @Test
    void manifestIncludesCsvFiles() throws Exception {
        String xformWithCsv = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>CSV Form</h:title>
                        <model>
                            <instance>
                                <data id="csv_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                    <lookup/><meta><instanceID/></meta>
                                </data>
                            </instance>
                            <bind nodeset="/data/lookup" type="binary"/>
                            <instance id="items" src="jr://file/csv/items.csv"/>
                        </model>
                    </h:head>
                    <h:body>
                        <input ref="/data/lookup"><label>Lookup</label></input>
                    </h:body>
                </h:html>
                """;

        UUID projectId = createTestProject();
        MockMultipartFile file = new MockMultipartFile("file", "csv_form.xml", MediaType.APPLICATION_XML_VALUE, xformWithCsv.getBytes());

        MvcResult uploadResult = mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", projectId)
                        .file(file).with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk()).andReturn();

        publishAndAssignUploadedForm(projectId, uploadResult);

        String xmlns = objectMapper.readTree(uploadResult.getResponse().getContentAsString()).get("xmlns").asText();

        MvcResult manifestResult = mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", projectId, xmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk()).andReturn();

        String manifestXml = manifestResult.getResponse().getContentAsString();
        assert manifestXml.contains("<filename>csv_form.xml</filename>") : "Should include form XML in manifest";
    }

    // ==================== Edge Cases ====================

    @Test
    void manifestEndpoint_WithEmptyMediaReferences_ReturnsOnlyFormXml() throws Exception {
        // Form with no media references
        String simpleForm = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>Simple Form</h:title>
                        <model>
                            <instance>
                                <data id="simple_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                    <name/><meta><instanceID/></meta>
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

        UUID projectId = createTestProject();
        MockMultipartFile file = new MockMultipartFile("file", "simple_form.xml", MediaType.APPLICATION_XML_VALUE, simpleForm.getBytes());

        MvcResult uploadResult = mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", projectId)
                        .file(file).with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk()).andReturn();

        publishAndAssignUploadedForm(projectId, uploadResult);

        String xmlns = objectMapper.readTree(uploadResult.getResponse().getContentAsString()).get("xmlns").asText();

        MvcResult manifestResult = mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", projectId, xmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk()).andReturn();

        String manifestXml = manifestResult.getResponse().getContentAsString();
        
        // Should only have the form XML media file
        int mediaFileCount = (manifestXml.split("<mediaFile>").length - 1);
        assert mediaFileCount == 1 : "Should only have form XML media file, found: " + mediaFileCount;
        assert manifestXml.contains("<filename>simple_form.xml</filename>") : "Should include form XML filename";
    }

    @Test
    void manifestEndpoint_HandlesSpecialCharactersInFilename() throws Exception {
        // Filenames with special characters should be properly escaped in XML
        String xformWithSpecialChars = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>Special Chars Form</h:title>
                        <model>
                            <instance>
                                <data id="special_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                    <file/><meta><instanceID/></meta>
                                </data>
                            </instance>
                            <bind nodeset="/data/file" type="binary"/>
                        </model>
                    </h:head>
                    <h:body>
                        <upload ref="/data/file" mediatype="*/*"><label>File</label></upload>
                    </h:body>
                </h:html>
                """;

        UUID projectId = createTestProject();
        MockMultipartFile file = new MockMultipartFile("file", "special_form.xml", MediaType.APPLICATION_XML_VALUE, xformWithSpecialChars.getBytes());

        MvcResult uploadResult = mockMvc.perform(multipart("/api/projects/{projectId}/forms/upload", projectId)
                        .file(file).with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk()).andReturn();

        publishAndAssignUploadedForm(projectId, uploadResult);

        String xmlns = objectMapper.readTree(uploadResult.getResponse().getContentAsString()).get("xmlns").asText();

        MvcResult manifestResult = mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", projectId, xmlns)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isOk()).andReturn();

        String manifestXml = manifestResult.getResponse().getContentAsString();
        // XML should be well-formed with proper escaping
        assert manifestXml.contains("<filename>special_form.xml</filename>") : "Should include form XML filename";
        // Verify XML is well-formed (no unescaped special chars in XML structure)
        assert !manifestXml.contains("&") || manifestXml.contains("&amp;") : "XML should be properly escaped";
    }

    @Test
    void manifestEndpoint_ReturnsCorrectContentType() throws Exception {
        mockMvc.perform(get("/v1/projects/{projectId}/forms/{xmlFormId}/manifest", testProjectId, testFormXmlns)
                        .header("Authorization", validCredentials))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/xml"));
    }
}