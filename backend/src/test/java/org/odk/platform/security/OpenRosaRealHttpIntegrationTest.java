package org.odk.platform.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.odk.platform.domain.AppUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * M2 synthetic loopback HTTP gate: runs the real server web stack on a random
 * port, real Basic auth, database-backed form discovery, XForm download,
 * manifest, submission HEAD, multipart POST, persistence, and duplicate retry.
 *
 * This is not an Android emulator or a physical-device test.
 * It uses only the ephemeral Spring test database and synthetic users/forms.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "odk.test.m2-real-http=true"
)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenRosaRealHttpIntegrationTest {

    private static final UUID TEST_PROJECT_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final String TEST_FORM_XMLNS = "m2_http_form";
    private static final String TEST_FORM_VERSION = "1";
    private static final String SAMPLE_XFORM_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                xmlns="http://www.w3.org/2002/xforms"
                xmlns:jr="http://openrosa.org/javarosa">
          <h:head>
            <h:title>M2 Synthetic HTTP Form</h:title>
            <model>
              <instance>
                <data id="m2_http_form" version="1">
                  <meta><instanceID/></meta><name/>
                </data>
              </instance>
            </model>
          </h:head>
          <h:body>
            <input ref="/data/name"><label>Respondent name</label></input>
          </h:body>
        </h:html>
        """;

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    private HttpClient client;
    private String authorization;
    private String projectBase;

    @BeforeAll
    void setup() {
        ensureOpenRosaFixture();
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        authorization = "Basic " + Base64.getEncoder().encodeToString(
            "m2_synthetic_appuser:synthetic-password-test-only".getBytes(StandardCharsets.UTF_8)
        );
        projectBase = "http://127.0.0.1:" + port + "/v1/projects/" + TEST_PROJECT_ID;
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
        UUID appUserId = ensureAppUser("m2_synthetic_appuser", "synthetic-password-test-only");
        ensureProject(ownerId);
        UUID formId = ensurePublishedForm(ownerId);
        ensureAppUserAssignment(appUserId, ownerId, formId);
    }

    private UUID ensureFixtureOwner() {
        String username = "m2_synthetic_owner";
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
                    "m2-owner@example.test",
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
                "m2-owner@example.test",
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
                    SET name = 'M2 Synthetic OpenRosa Project',
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
                VALUES (?, 'M2 Synthetic OpenRosa Project',
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
                            'M2 HTTP integration fixture',
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


    private HttpResponse<String> send(String method, URI uri, boolean withAuth, String body, String contentType) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20));
        if (withAuth) request.header("Authorization", authorization);
        request.header("User-Agent", "ODK Collect v2026.4.0 (M2 synthetic HTTP contract)");
        if ("POST".equals(method)) {
            request.header("Content-Type", contentType);
            request.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else if ("HEAD".equals(method)) {
            request.method("HEAD", HttpRequest.BodyPublishers.noBody());
        } else {
            request.GET();
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI localUri(String absoluteUrl) {
        URI uri = URI.create(absoluteUrl);
        assertEquals(port, uri.getPort(), "Generated URL must use the disposable local test server");
        assertTrue(List.of("127.0.0.1", "localhost").contains(uri.getHost()),
            "No test request may follow a form-list URL to an external host");
        assertEquals("http", uri.getScheme());
        return uri;
    }

    private String xmlValue(String content, String tag) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var doc = factory.newDocumentBuilder().parse(
            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        var node = doc.getElementsByTagName(tag).item(0);
        assertNotNull(node, "Missing OpenRosa XML tag: " + tag);
        return node.getTextContent();
    }

    @Test
    void realHttpDiscoveryDownloadSubmissionAndDuplicateRetry() throws Exception {
        URI listUrl = localUri(projectBase + "/formList?deviceID=android%3Am2-synthetic");
        var unauthorized = send("GET", listUrl, false, null, null);
        assertEquals(401, unauthorized.statusCode(), "Unauthenticated form list must challenge");

        var list = send("GET", listUrl, true, null, null);
        assertEquals(200, list.statusCode(), list.body());
        assertEquals("1.0", list.headers().firstValue("X-OpenRosa-Version").orElse(""));
        assertEquals(TEST_FORM_XMLNS, xmlValue(list.body(), "formID"));
        assertEquals(TEST_FORM_VERSION, xmlValue(list.body(), "version"));

        String downloadUrl = xmlValue(list.body(), "downloadUrl");
        String manifestUrl = xmlValue(list.body(), "manifestUrl");
        var download = send("GET", localUri(downloadUrl), true, null, null);
        assertEquals(200, download.statusCode(), download.body());
        assertTrue(download.body().contains("m2_http_form"));

        // Verify the downloaded XForm bytes match form-list hash for cache correctness.
        byte[] bytes = download.body().getBytes(StandardCharsets.UTF_8);
        String hex = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        assertEquals("md5:" + hex, xmlValue(list.body(), "hash"));

        var manifest = send("GET", localUri(manifestUrl), true, null, null);
        assertEquals(200, manifest.statusCode(), manifest.body());
        assertTrue(manifest.body().contains("<manifest"));

        URI submissionUrl = localUri(projectBase + "/submission?deviceID=android%3Am2-synthetic");
        var head = send("HEAD", submissionUrl, true, null, null);
        assertEquals(204, head.statusCode());
        assertEquals("104857600", head.headers()
            .firstValue("X-OpenRosa-Accept-Content-Length").orElse(""));

        String instanceId = "uuid:" + UUID.randomUUID();
        String xml = "<data id=\"m2_http_form\" version=\"1\">"
            + "<meta><instanceID>" + instanceId + "</instanceID></meta>"
            + "<name>M2 Synthetic Respondent</name></data>";
        String boundary = "m2SyntheticBoundary";
        String multipart = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"xml_submission_file\"; filename=\"submission.xml\"\r\n"
            + "Content-Type: text/xml\r\n\r\n"
            + xml + "\r\n--" + boundary + "--\r\n";

        var first = send("POST", submissionUrl, true, multipart,
            "multipart/form-data; boundary=" + boundary);
        assertEquals(201, first.statusCode(), first.body());
        assertEquals(instanceId, xmlValue(first.body(), "instanceID"));

        Integer records = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM submissions WHERE instance_id = ?", Integer.class, instanceId);
        assertEquals(1, records);

        var retry = send("POST", submissionUrl, true, multipart,
            "multipart/form-data; boundary=" + boundary);
        assertEquals(409, retry.statusCode(), "Retry must reject duplicate instance ID");
        Integer afterRetry = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM submissions WHERE instance_id = ?", Integer.class, instanceId);
        assertEquals(1, afterRetry, "Retry must not insert a second submission");
    }
}
