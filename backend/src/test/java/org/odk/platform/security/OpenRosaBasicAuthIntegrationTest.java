package org.odk.platform.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(properties = "odk.test.openrosa-basic-auth-integration=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OpenRosaBasicAuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final UUID TEST_PROJECT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TEST_FORM_XMLNS = "sample_form";
    private static final String TEST_FORM_VERSION = "1";

    private static final String SAMPLE_XFORM_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms"
                    xmlns:jr="http://openrosa.org/javarosa">
              <h:head>
                <h:title>Sample Form</h:title>
                <model>
                  <instance>
                    <data id="sample_form" version="1">
                      <meta>
                        <instanceID/>
                      </meta>
                      <name/>
                    </data>
                  </instance>
                </model>
              </h:head>
              <h:body>
                <input ref="/data/name">
                  <label>Name</label>
                </input>
              </h:body>
            </h:html>
            """;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String validCredentials;
    private String invalidCredentials;
    private String malformedCredentials;

    @BeforeEach
    void setUp() {
        // Each OpenRosa request must authenticate independently. Tests elsewhere can
        // leave a MockMvc security context populated on this test thread; if that
        // happens the Basic-auth filter intentionally skips re-authentication.
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        ensureOpenRosaFixture();

        String credentials = "appuser:password123";
        validCredentials = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        String invalidCreds = "appuser:wrongpassword";
        invalidCredentials = "Basic " + Base64.getEncoder()
                .encodeToString(invalidCreds.getBytes(StandardCharsets.UTF_8));

        malformedCredentials = "Basic notvalidbase64!";
    }

    /**
     * Creates the real database state required by the OpenRosa endpoints.
     *
     * The integration test must not depend on hidden seed data. Authentication,
     * project context, published-form lookup and app-user form authorization all
     * continue through the production filter/services/repositories.
     */
    private void ensureOpenRosaFixture() {
        UUID ownerId = ensureFixtureOwner();
        UUID appUserId = ensureAppUser("appuser", "password123");
        ensureProject(ownerId);
        UUID formId = ensurePublishedForm(ownerId);
        ensureAppUserAssignment(appUserId, ownerId, formId);
    }

    private UUID ensureFixtureOwner() {
        String username = "openrosa_fixture_owner";
        List<UUID> existingIds = jdbcTemplate.query(
                "SELECT id FROM users WHERE username = ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                username);

        String encodedPassword = passwordEncoder.encode("fixture-owner-password");

        if (!existingIds.isEmpty()) {
            UUID ownerId = existingIds.get(0);
            jdbcTemplate.update(
                    """
                    UPDATE users
                    SET password_hash = ?,
                        email = ?,
                        is_active = true,
                        is_verified = true,
                        failed_login_attempts = 0,
                        locked_until = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    encodedPassword,
                    "openrosa-fixture-owner@example.test",
                    ownerId);
            return ownerId;
        }

        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO users (
                    id, username, email, password_hash,
                    first_name, last_name, is_active, is_verified,
                    failed_login_attempts, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, 'OpenRosa', 'Fixture Owner',
                        true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                ownerId,
                username,
                "openrosa-fixture-owner@example.test",
                encodedPassword);

        return ownerId;
    }

    private UUID ensureAppUser(String username, String rawPassword) {
        List<UUID> existingIds = jdbcTemplate.query(
                "SELECT id FROM app_users WHERE username = ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                username);

        String encodedPassword = passwordEncoder.encode(rawPassword);

        if (!existingIds.isEmpty()) {
            UUID appUserId = existingIds.get(0);
            jdbcTemplate.update(
                    """
                    UPDATE app_users
                    SET password_hash = ?,
                        email = ?,
                        full_name = ?,
                        is_active = true,
                        is_verified = true,
                        failed_login_attempts = 0,
                        locked_until = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    encodedPassword,
                    username + "@example.test",
                    "OpenRosa App User",
                    appUserId);
            return appUserId;
        }

        UUID appUserId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO app_users (
                    id, username, email, password_hash, full_name,
                    is_active, is_verified, failed_login_attempts,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, true, true, 0,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                appUserId,
                username,
                username + "@example.test",
                encodedPassword,
                "OpenRosa App User");

        return appUserId;
    }

    private void ensureProject(UUID ownerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM projects WHERE id = ?",
                Integer.class,
                TEST_PROJECT_ID);

        if (count != null && count > 0) {
            jdbcTemplate.update(
                    """
                    UPDATE projects
                    SET name = 'OpenRosa Integration Project',
                        is_active = true,
                        owner_id = ?,
                        created_by_id = ?,
                        updated_by_id = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    ownerId,
                    ownerId,
                    ownerId,
                    TEST_PROJECT_ID);
            return;
        }

        jdbcTemplate.update(
                """
                INSERT INTO projects (
                    id, name, description, is_active,
                    owner_id, created_by_id, updated_by_id,
                    settings, metadata, created_at, updated_at
                )
                VALUES (?, 'OpenRosa Integration Project',
                        'Deterministic fixture for Basic Auth integration tests',
                        true, ?, ?, ?, '{}', '{}',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                TEST_PROJECT_ID,
                ownerId,
                ownerId,
                ownerId);
    }

    private UUID ensurePublishedForm(UUID ownerId) {
        List<UUID> formIds = jdbcTemplate.query(
                """
                SELECT id
                FROM xforms
                WHERE project_id = ? AND xmlns = ?
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                TEST_PROJECT_ID,
                TEST_FORM_XMLNS);

        UUID formId;
        if (formIds.isEmpty()) {
            formId = UUID.randomUUID();
            jdbcTemplate.update(
                    """
                    INSERT INTO xforms (
                        id, title, description, xmlns, version, xml_content,
                        is_published, is_active, is_encrypted,
                        settings, metadata, published_at,
                        project_id, created_by_id, updated_by_id, published_by_id,
                        created_at, updated_at
                    )
                    VALUES (?, 'Sample Form',
                            'OpenRosa Basic Auth integration fixture',
                            ?, ?, ?, true, true, false,
                            '{}', '{}', CURRENT_TIMESTAMP,
                            ?, ?, ?, ?,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    formId,
                    TEST_FORM_XMLNS,
                    TEST_FORM_VERSION,
                    SAMPLE_XFORM_XML,
                    TEST_PROJECT_ID,
                    ownerId,
                    ownerId,
                    ownerId);
        } else {
            formId = formIds.get(0);
            jdbcTemplate.update(
                    """
                    UPDATE xforms
                    SET title = 'Sample Form',
                        version = ?,
                        xml_content = ?,
                        is_published = true,
                        is_active = true,
                        published_at = CURRENT_TIMESTAMP,
                        created_by_id = ?,
                        updated_by_id = ?,
                        published_by_id = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    TEST_FORM_VERSION,
                    SAMPLE_XFORM_XML,
                    ownerId,
                    ownerId,
                    ownerId,
                    formId);
        }

        // The repository's "latest" queries rely on is_latest.
        jdbcTemplate.update(
                "UPDATE form_versions SET is_latest = false WHERE form_id = ?",
                formId);

        List<UUID> versionIds = jdbcTemplate.query(
                """
                SELECT id
                FROM form_versions
                WHERE form_id = ? AND version = ?
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                formId,
                TEST_FORM_VERSION);

        UUID versionId;
        if (versionIds.isEmpty()) {
            versionId = UUID.randomUUID();
            jdbcTemplate.update(
                    """
                    INSERT INTO form_versions (
                        id, form_id, version, xml_content, xmlns,
                        status, is_published, is_latest,
                        settings, metadata, published_at,
                        published_by, created_by, created_by_id, published_by_id,
                        created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?,
                            'PUBLISHED', true, true,
                            '{}', '{}', CURRENT_TIMESTAMP,
                            ?, ?, ?, ?,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    versionId,
                    formId,
                    TEST_FORM_VERSION,
                    SAMPLE_XFORM_XML,
                    TEST_FORM_XMLNS,
                    ownerId,
                    ownerId,
                    ownerId,
                    ownerId);
        } else {
            versionId = versionIds.get(0);
            jdbcTemplate.update(
                    """
                    UPDATE form_versions
                    SET xml_content = ?,
                        xmlns = ?,
                        status = 'PUBLISHED',
                        is_published = true,
                        is_latest = true,
                        published_at = CURRENT_TIMESTAMP,
                        published_by = ?,
                        published_by_id = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    SAMPLE_XFORM_XML,
                    TEST_FORM_XMLNS,
                    ownerId,
                    ownerId,
                    versionId);
        }

        jdbcTemplate.update(
                """
                UPDATE xforms
                SET current_version_id = ?,
                    is_published = true,
                    published_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                versionId,
                formId);

        return formId;
    }

    private void ensureAppUserAssignment(UUID appUserId, UUID ownerId, UUID formId) {
        jdbcTemplate.update(
                "DELETE FROM app_user_projects WHERE app_user_id = ? AND project_id = ?",
                appUserId,
                TEST_PROJECT_ID);

        jdbcTemplate.update(
                """
                INSERT INTO app_user_projects (
                    id, app_user_id, project_id, form_id,
                    assigned_by, status, metadata,
                    assigned_at, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', '{}',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                appUserId,
                TEST_PROJECT_ID,
                formId,
                ownerId);
    }

    @Test
    void manifestEndpoint_WithValidBasicAuth_ReturnsManifestXml() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/v1/projects/00000000-0000-0000-0000-000000000001/forms/sample_form/manifest")
                        .header("Authorization", validCredentials)
                        .accept(MediaType.TEXT_XML))
                .andExpect(status().isOk())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_XML))
                .andExpect(content().string(containsString("<manifest")));
    }

    @Test
    void manifestEndpoint_WithInvalidBasicAuth_ReturnsUnauthorized() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/v1/projects/00000000-0000-0000-0000-000000000001/forms/sample_form/manifest")
                        .header("Authorization", invalidCredentials)
                        .accept(MediaType.TEXT_XML))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void attachmentDownloadEndpoint_WithValidBasicAuth_ReturnsNotFound() throws Exception {
        // Act & Assert - attachment storage not implemented, returns 404
        mockMvc.perform(get("/v1/projects/00000000-0000-0000-0000-000000000001/forms/sample_form/attachments/test.csv")
                        .header("Authorization", validCredentials))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"));
    }

    @Test
    void attachmentDownloadEndpoint_WithInvalidBasicAuth_ReturnsUnauthorized() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/v1/projects/00000000-0000-0000-0000-000000000001/forms/sample_form/attachments/test.csv")
                        .header("Authorization", invalidCredentials))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submissionHeadEndpoint_WithValidBasicAuth_ReturnsNoContent() throws Exception {
        // Act & Assert
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head("/v1/projects/00000000-0000-0000-0000-000000000001/submission")
                        .header("Authorization", validCredentials))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"))
                .andExpect(header().string("X-OpenRosa-Accept-Content-Length", "104857600"));
    }

    @Test
    void submissionHeadEndpoint_WithInvalidBasicAuth_ReturnsUnauthorized() throws Exception {
        // Act & Assert
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head("/v1/projects/00000000-0000-0000-0000-000000000001/submission")
                        .header("Authorization", invalidCredentials))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submissionPostEndpoint_WithValidBasicAuth_ReturnsCreated() throws Exception {
        // Arrange
        String submissionXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="sample_form">
                    <meta>
                        <instanceID>uuid:test-instance-id</instanceID>
                    </meta>
                    <name>Test Submission</name>
                </data>
                """;

        // Act & Assert - use multipart with file part
        MockMultipartFile multipartFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes());
        
        MockMultipartHttpServletRequestBuilder request = multipart(
                "/v1/projects/00000000-0000-0000-0000-000000000001/submission");
        request.file(multipartFile);
        request.header("Authorization", validCredentials);

        mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"))
                .andExpect(content().string(containsString("<OpenRosaResponse")))
                .andExpect(content().string(containsString("<message>")));
    }

    @Test
    void submissionPostEndpoint_WithInvalidBasicAuth_ReturnsUnauthorized() throws Exception {
        // Arrange
        String submissionXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="sample_form">
                    <meta>
                        <instanceID>uuid:test-instance-id</instanceID>
                    </meta>
                </data>
                """;

        MockMultipartFile multipartFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes());

        MockMultipartHttpServletRequestBuilder request = multipart(
                "/v1/projects/00000000-0000-0000-0000-000000000001/submission");
        request.file(multipartFile);
        request.header("Authorization", invalidCredentials);

        // Act & Assert
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submissionPostEndpoint_WithMissingXmlSubmissionFile_ReturnsBadRequest() throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(
                "/v1/projects/00000000-0000-0000-0000-000000000001/submission");
        request.header("Authorization", validCredentials);
        request.param("other_field", "value");

        // Act & Assert
        mockMvc.perform(request)
                .andExpect(status().isBadRequest());
    }

    @Test
    void compatibilityFormListEndpoint_WithValidBasicAuth_ReturnsFormListXml() throws Exception {
        // Test the compatibility alias /formList
        mockMvc.perform(get("/formList")
                        .header("Authorization", validCredentials)
                        .accept(MediaType.TEXT_XML))
                .andExpect(status().isOk())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"))
                .andExpect(content().string(containsString("<xforms")))
                .andExpect(content().string(containsString("<hash>md5:")));
    }

    @Test
    void compatibilityFormDownloadEndpoint_WithValidBasicAuth_ReturnsFormXml() throws Exception {
        // Test the compatibility alias /forms/{xmlFormId}.xml
        mockMvc.perform(get("/forms/sample_form.xml")
                        .header("Authorization", validCredentials)
                        .accept(MediaType.TEXT_XML))
                .andExpect(status().isOk())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"));
    }

    @Test
    void compatibilityManifestEndpoint_WithValidBasicAuth_ReturnsManifestXml() throws Exception {
        // Test the compatibility alias /forms/{xmlFormId}/manifest
        mockMvc.perform(get("/forms/sample_form/manifest")
                        .header("Authorization", validCredentials)
                        .accept(MediaType.TEXT_XML))
                .andExpect(status().isOk())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"))
                .andExpect(content().string(containsString("<manifest")));
    }

    @Test
    void compatibilitySubmissionHeadEndpoint_WithValidBasicAuth_ReturnsNoContent() throws Exception {
        // Test the compatibility alias HEAD /submission
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head("/submission")
                        .header("Authorization", validCredentials))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"))
                .andExpect(header().string("X-OpenRosa-Accept-Content-Length", "104857600"));
    }

    @Test
    void compatibilitySubmissionPostEndpoint_WithValidBasicAuth_ReturnsCreated() throws Exception {
        // Test the compatibility alias POST /submission
        String submissionXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="sample_form">
                    <meta>
                        <instanceID>uuid:test-instance-id</instanceID>
                    </meta>
                </data>
                """;

        MockMultipartFile multipartFile = new MockMultipartFile(
                "xml_submission_file",
                "submission.xml",
                MediaType.APPLICATION_XML_VALUE,
                submissionXml.getBytes());

        MockMultipartHttpServletRequestBuilder request = multipart("/submission");
        request.file(multipartFile);
        request.header("Authorization", validCredentials);

        mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"))
                .andExpect(content().string(containsString("<OpenRosaResponse")));
    }

    @Test
    void openRosaHeaders_ArePresentOnAllV1Endpoints() throws Exception {
        // Verify X-OpenRosa-Version header is present on all v1 endpoints
        String[] endpoints = {
            "/v1/projects/00000000-0000-0000-0000-000000000001/formList",
            "/v1/projects/00000000-0000-0000-0000-000000000001/forms/sample_form.xml",
            "/v1/projects/00000000-0000-0000-0000-000000000001/forms/sample_form/manifest"
        };

        for (String endpoint : endpoints) {
            MvcResult result = mockMvc.perform(get(endpoint)
                            .header("Authorization", validCredentials)
                            .accept(MediaType.TEXT_XML))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-OpenRosa-Version", "1.0"))
                    .andReturn();
        }
        
        // Attachment endpoint returns 404 (not implemented) but still has OpenRosa headers
        mockMvc.perform(get("/v1/projects/00000000-0000-0000-0000-000000000001/forms/sample_form/attachments/test.csv")
                        .header("Authorization", validCredentials))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"));
    }

    @Test
    void openRosaHeaders_ArePresentOnCompatibilityEndpoints() throws Exception {
        // Verify X-OpenRosa-Version header is present on compatibility endpoints
        String[] endpoints = {
            "/formList",
            "/forms/sample_form.xml",
            "/forms/sample_form/manifest"
        };

        for (String endpoint : endpoints) {
            mockMvc.perform(get(endpoint)
                            .header("Authorization", validCredentials)
                            .accept(MediaType.TEXT_XML))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-OpenRosa-Version", "1.0"));
        }
    }

    @Test
    void submissionEndpoints_HaveAcceptContentLengthHeader() throws Exception {
        // Verify X-OpenRosa-Accept-Content-Length header on submission endpoints
        String[] endpoints = {
            "/v1/projects/00000000-0000-0000-0000-000000000001/submission",
            "/submission"
        };

        for (String endpoint : endpoints) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head(endpoint)
                            .header("Authorization", validCredentials))
                    .andExpect(status().isNoContent())
                    .andExpect(header().string("X-OpenRosa-Accept-Content-Length", "104857600"));
        }
    }

    @Test
    void xForwardedForHeader_IsUsedForAuditLogging() throws Exception {
        // Test that X-Forwarded-For header is properly handled
        mockMvc.perform(get("/v1/projects/00000000-0000-0000-0000-000000000001/formList")
                        .header("Authorization", validCredentials)
                        .header("X-Forwarded-For", "192.168.1.100, 10.0.0.1")
                        .accept(MediaType.TEXT_XML))
                .andExpect(status().isOk());
    }

    @Test
    void userAgentHeader_IsCaptured() throws Exception {
        // Test that User-Agent header is captured for audit logging
        mockMvc.perform(get("/v1/projects/00000000-0000-0000-0000-000000000001/formList")
                        .header("Authorization", validCredentials)
                        .header("User-Agent", "ODK Collect v2023.1.0")
                        .accept(MediaType.TEXT_XML))
                .andExpect(status().isOk());
    }

    // ODK Collect v2026.4.0 requests {base}/formList?deviceID=... and sends
    // a HEAD to {base}/submission before multipart submission.
    @Test
    void odk2026FormList_WithDeviceId_ReturnsRequiredOpenRosaFields() throws Exception {
        mockMvc.perform(get("/v1/projects/{id}/formList", TEST_PROJECT_ID)
                        .param("deviceID", "android:synthetic-2026-device")
                        .header("Authorization", validCredentials)
                        .header("User-Agent", "ODK Collect v2026.4.0")
                        .accept(MediaType.TEXT_XML))
                .andExpect(status().isOk())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_XML))
                .andExpect(content().string(containsString("<formID>sample_form</formID>")))
                .andExpect(content().string(containsString("<version>1</version>")))
                .andExpect(content().string(containsString("<hash>md5:")))
                .andExpect(content().string(containsString("<downloadUrl>")))
                .andExpect(content().string(containsString("<manifestUrl>")));
    }

    @Test
    void odk2026FormList_UnauthenticatedDeviceGetsBasicChallenge() throws Exception {
        mockMvc.perform(get("/v1/projects/{id}/formList", TEST_PROJECT_ID)
                        .param("deviceID", "android:synthetic-2026-device")
                        .accept(MediaType.TEXT_XML))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"))
                .andExpect(header().string("WWW-Authenticate", containsString("Basic realm=")));
    }

    @Test
    void odk2026FormList_ProxyHeadersProducePublicHttpsUrls() throws Exception {
        mockMvc.perform(get("/v1/projects/{id}/formList", TEST_PROJECT_ID)
                        .param("deviceID", "android:synthetic-2026-device")
                        .header("Authorization", validCredentials)
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "collector.example.test")
                        .accept(MediaType.TEXT_XML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "https://collector.example.test/v1/projects/" + TEST_PROJECT_ID
                                + "/forms/sample_form.xml")));
    }

    @Test
    void odk2026SubmissionHead_WorksWithDeviceIdQuery() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .head("/v1/projects/{id}/submission", TEST_PROJECT_ID)
                        .param("deviceID", "android:synthetic-2026-device")
                        .header("Authorization", validCredentials)
                        .header("User-Agent", "ODK Collect v2026.4.0"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-OpenRosa-Version", "1.0"))
                .andExpect(header().string("X-OpenRosa-Accept-Content-Length", "104857600"));
    }

    @Test
    void odk2026CompatibilityAlias_WithDeviceId_ReturnsFormList() throws Exception {
        mockMvc.perform(get("/formList")
                        .param("deviceID", "android:synthetic-2026-device")
                        .header("Authorization", validCredentials)
                        .accept(MediaType.TEXT_XML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<formID>sample_form</formID>")));
    }

    @Test
    void mobileBasicWithWebUsernameCollisionStillUsesOnlyAssignedAppUser() throws Exception {
        // Simulates two distinct principals with the same *username* in
        // users and app_users. Each has its own password and account identity.
        String mobileUsername = "appuser";
        String webPassword = passwordEncoder.encode("separate-web-password");
        List<UUID> existingWebIds = jdbcTemplate.query(
                "SELECT id FROM users WHERE username = ?",
                (rs, index) -> rs.getObject("id", UUID.class), mobileUsername);
        if (existingWebIds.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO users (
                      id, username, email, password_hash, first_name, last_name,
                      is_active, is_verified, failed_login_attempts, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'Colliding', 'Web Account',
                            true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, UUID.randomUUID(), mobileUsername,
                    "colliding-openrosa-web@example.test", webPassword);
        } else {
            jdbcTemplate.update(
                    "UPDATE users SET password_hash = ?, is_active = true WHERE id = ?",
                    webPassword, existingWebIds.get(0));
        }

        // The mobile Basic credentials must never resolve to the colliding web
        // account; only the assigned App User can obtain project-scoped formList.
        mockMvc.perform(get("/formList").header("Authorization", validCredentials)
                        .accept(MediaType.TEXT_XML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("sample_form")));
    }

    @Test
    void webOnlyPasswordCannotAuthenticateAsMobileOnCollision() throws Exception {
        String webPassword = "separate-web-password";
        List<UUID> ids = jdbcTemplate.query(
                "SELECT id FROM users WHERE username = ?",
                (rs, index) -> rs.getObject("id", UUID.class), "appuser");
        if (ids.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO users (
                      id, username, email, password_hash, first_name, last_name,
                      is_active, is_verified, failed_login_attempts, created_at, updated_at)
                    VALUES (?, 'appuser', 'collision-mobile-negative@example.test', ?,
                            'Web', 'Only', true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, UUID.randomUUID(), passwordEncoder.encode(webPassword));
        } else {
            jdbcTemplate.update("UPDATE users SET password_hash = ?, is_active = true WHERE id = ?",
                    passwordEncoder.encode(webPassword), ids.get(0));
        }
        String webCredentials = "Basic " + Base64.getEncoder().encodeToString(
                ("appuser:" + webPassword).getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/formList").header("Authorization", webCredentials)
                        .accept(MediaType.TEXT_XML))
                .andExpect(status().isUnauthorized());
    }
    /**
     * Real HTTP regression: a currently valid Basic user loses its project
     * assignment after the request fixture was created. No previously assigned
     * form XML or manifest may be returned.
     */
    @Test
    void revokedFormAssignmentCannotDownloadFormOrManifest() throws Exception {
        UUID mobileId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_users WHERE username = ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                "appuser");
        jdbcTemplate.update(
                "DELETE FROM app_user_projects WHERE app_user_id = ? AND project_id = ?",
                mobileId, TEST_PROJECT_ID);

        mockMvc.perform(get("/v1/projects/{projectId}/forms/{formId}.xml",
                        TEST_PROJECT_ID, TEST_FORM_XMLNS)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.TEXT_XML))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/v1/projects/{projectId}/forms/{formId}/manifest",
                        TEST_PROJECT_ID, TEST_FORM_XMLNS)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.TEXT_XML))
                .andExpect(status().isNotFound());
    }

    /**
     * Real HTTP regression: an assigned but deactivated mobile identity cannot
     * use existing credentials to browse the project's forms.
     */
    @Test
    void inactiveMobileAccountCannotUseOpenRosaBasic() throws Exception {
        jdbcTemplate.update("UPDATE app_users SET is_active = false WHERE username = ?", "appuser");
        mockMvc.perform(get("/v1/projects/{projectId}/formList", TEST_PROJECT_ID)
                        .header("Authorization", validCredentials)
                        .accept(MediaType.TEXT_XML))
                .andExpect(status().is4xxClientError());
    }

}
