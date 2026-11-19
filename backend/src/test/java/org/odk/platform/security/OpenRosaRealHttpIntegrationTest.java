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

    private UUID m3VersionTwo() {
        UUID formId = jdbcTemplate.queryForObject(
            "SELECT id FROM xforms WHERE project_id = ? AND xmlns = ?",
            UUID.class, TEST_PROJECT_ID, TEST_FORM_XMLNS);
        List<UUID> present = jdbcTemplate.query(
            "SELECT id FROM form_versions WHERE form_id = ? AND version = '2'",
            (rs, row) -> rs.getObject("id", UUID.class), formId);
        if (!present.isEmpty()) return present.get(0);
        UUID id = UUID.randomUUID();
        UUID owner = ensureFixtureOwner();
        String xml = SAMPLE_XFORM_XML
            .replace("version=\"1\"", "version=\"2\"")
            .replace("<meta><instanceID/></meta><name/>",
                    "<meta><instanceID/><deprecatedID/></meta><name/><followUpNote/>")
            .replace("</h:body>",
                    "<input ref=\"/data/followUpNote\"><label>Visit notes</label></input></h:body>");
        jdbcTemplate.update("""
                INSERT INTO form_versions
                (id, form_id, version, xml_content, xmlns,
                 status, is_published, is_latest, settings, metadata,
                 published_at, published_by, created_by, created_by_id,
                 published_by_id, created_at, updated_at)
                VALUES (?, ?, '2', ?, ?,
                 'PUBLISHED', true, false, '{}', '{}',
                 CURRENT_TIMESTAMP, ?, ?, ?, ?,
                 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, formId, xml, TEST_FORM_XMLNS, owner, owner, owner, owner);
        return id;
    }

    private String m3Xml(String version, String instanceId, String deprecatedId, String name) {
        String meta = "<meta><instanceID>" + instanceId + "</instanceID>"
            + (deprecatedId == null ? "" : "<deprecatedID>" + deprecatedId + "</deprecatedID>")
            + "</meta>";
        return "<data id=\"m2_http_form\" version=\"" + version + "\">"
            + meta + "<name>" + name + "</name>"
            + ("2".equals(version) ? "<followUpNote>synthetic note</followUpNote>" : "")
            + "</data>";
    }

    private String multipartM3(String xml) {
        return "--m3Boundary\r\nContent-Disposition: form-data; "
                + "name=\"xml_submission_file\"; filename=\"record.xml\"\r\n"
                + "Content-Type: text/xml\r\n\r\n" + xml
                + "\r\n--m3Boundary--\r\n";
    }

    private HttpResponse<String> sendM3(String method, String url, String body, String workId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(localUri(url))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", authorization);
        if (workId != null) request.header("X-DataCollector-Work-Item-Id", workId);
        if ("POST".equals(method)) {
            boolean multipart = url.contains("/submission");
            request.header("Content-Type",
                multipart ? "multipart/form-data; boundary=m3Boundary" : "application/json");
            request.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            request.GET();
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String m3CreateWork(UUID source, UUID targetVersion, String kind,
                                String visitKey, String requestId) {
        return "{\"sourceSubmissionId\":\"" + source + "\","
            + "\"targetFormVersionId\":\"" + targetVersion + "\","
            + "\"workType\":\"" + kind + "\","
            + "\"clientRequestId\":\"" + requestId + "\","
            + "\"visitKey\":" + (visitKey == null ? "null" : "\"" + visitKey + "\"") + "}";
    }

    @Test
    void m3NewSecondVisitIsNewSubmissionAndNeverReplacesVisitOne() throws Exception {
        UUID targetVersion = m3VersionTwo();
        String firstInstance = "uuid:" + UUID.randomUUID();
        var first = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("1", firstInstance, null, "Household at Visit One")), null);
        assertEquals(201, first.statusCode(), first.body());
        UUID sourceId = jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE instance_id = ?", UUID.class, firstInstance);
        String sourceXml = jdbcTemplate.queryForObject(
            "SELECT xml_content FROM submissions WHERE id = ?", String.class, sourceId);

        String requestId = "visit-" + UUID.randomUUID();
        String visitKey = "2026-Q4-second-visit-" + UUID.randomUUID();
        String payload = m3CreateWork(sourceId, targetVersion, "FOLLOW_UP", visitKey, requestId);
        var created = sendM3("POST", projectBase + "/record-work-items", payload, null);
        assertEquals(201, created.statusCode(), created.body());
        String workId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(created.body()).get("id").asText();

        var retriedTask = sendM3("POST", projectBase + "/record-work-items", payload, null);
        assertEquals(201, retriedTask.statusCode());
        assertEquals(workId, new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(retriedTask.body()).get("id").asText());

        var preparation = sendM3("GET",
            projectBase + "/record-work-items/" + workId + "/prepare", null, null);
        assertEquals(200, preparation.statusCode(), preparation.body());
        assertTrue(preparation.body().contains("WITHOUT deprecatedID"));
        assertTrue(preparation.body().contains("mappingApprovalRequired"));
        assertTrue(preparation.body().contains("version=\\\"2\\\""));

        String secondInstance = "uuid:" + UUID.randomUUID();
        var second = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("2", secondInstance, null, "Household at Visit Two")), workId);
        assertEquals(201, second.statusCode(), second.body());
        assertEquals(sourceXml, jdbcTemplate.queryForObject(
            "SELECT xml_content FROM submissions WHERE id = ?", String.class, sourceId));
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM record_links
            WHERE source_submission_id = ? AND link_type = 'FOLLOW_UP'
            """, Integer.class, sourceId));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM submission_versions WHERE submission_id = ?",
            Integer.class, sourceId));

        // A malicious second completion with a different instance ID must roll
        // the new submission back, not create another linked visit.
        String invalidInstance = "uuid:" + UUID.randomUUID();
        var retry = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("2", invalidInstance, null, "Duplicate second visit")), workId);
        assertEquals(409, retry.statusCode(), retry.body());
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM submissions WHERE instance_id = ?",
            Integer.class, invalidInstance));
    }

    @Test
    void m3AmendmentOnPublishedVersionTwoPreservesVersionOneHistory() throws Exception {
        UUID targetVersion = m3VersionTwo();
        String firstInstance = "uuid:" + UUID.randomUUID();
        var initial = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("1", firstInstance, null, "Prior answers")), null);
        assertEquals(201, initial.statusCode(), initial.body());
        UUID sourceId = jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE instance_id = ?", UUID.class, firstInstance);
        UUID sourceVersion = jdbcTemplate.queryForObject(
            "SELECT form_version_id FROM submissions WHERE id = ?", UUID.class, sourceId);
        String payload = m3CreateWork(sourceId, targetVersion, "AMENDMENT", null,
            "amend-" + UUID.randomUUID());
        var created = sendM3("POST", projectBase + "/record-work-items", payload, null);
        assertEquals(201, created.statusCode(), created.body());
        String workId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(created.body()).get("id").asText();

        String amendedInstance = "uuid:" + UUID.randomUUID();
        var replacement = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("2", amendedInstance, firstInstance, "Corrected answers")), workId);
        assertEquals(201, replacement.statusCode(), replacement.body());
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM record_links
            WHERE source_submission_id = ? AND link_type = 'AMENDMENT'
            """, Integer.class, sourceId));
        assertEquals(sourceVersion, jdbcTemplate.queryForObject("""
            SELECT source_form_version_id FROM submission_versions
            WHERE submission_id = ? ORDER BY created_at DESC LIMIT 1
            """, UUID.class, sourceId));
        assertEquals(targetVersion, jdbcTemplate.queryForObject(
            "SELECT form_version_id FROM submissions WHERE instance_id = ?",
            UUID.class, amendedInstance));
    }

    private UUID m3SeparateFollowUpForm() {
        String followUpId = "m3_followup_form";
        List<UUID> formIds = jdbcTemplate.query(
            "SELECT id FROM xforms WHERE project_id = ? AND xmlns = ?",
            (rs, row) -> rs.getObject("id", UUID.class), TEST_PROJECT_ID, followUpId);
        UUID owner = ensureFixtureOwner();
        UUID formId;
        if (formIds.isEmpty()) {
            formId = UUID.randomUUID();
            String xform = SAMPLE_XFORM_XML.replace(TEST_FORM_XMLNS, followUpId)
                .replace("M2 Synthetic HTTP Form", "Second Household Visit");
            jdbcTemplate.update("""
                    INSERT INTO xforms
                    (id, title, description, xmlns, version, xml_content, is_published,
                     is_active, is_encrypted, settings, metadata, published_at,
                     project_id, created_by_id, updated_by_id, published_by_id,
                     created_at, updated_at)
                    VALUES (?, 'Second Household Visit', 'Independent periodic observation',
                     ?, '1', ?, true, true, false, '{}', '{}', CURRENT_TIMESTAMP,
                     ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, formId, followUpId, xform, TEST_PROJECT_ID, owner, owner, owner);
            UUID versionId = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO form_versions
                    (id, form_id, version, xml_content, xmlns, status,
                     is_published, is_latest, settings, metadata,
                     published_at, published_by, created_by, created_by_id, published_by_id,
                     created_at, updated_at)
                    VALUES (?, ?, '1', ?, ?, 'PUBLISHED', true, true, '{}', '{}',
                     CURRENT_TIMESTAMP, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, versionId, formId, xform, followUpId, owner, owner, owner, owner);
        } else {
            formId = formIds.get(0);
        }
        UUID appUser = jdbcTemplate.queryForObject(
            "SELECT id FROM app_users WHERE username = 'm2_synthetic_appuser'", UUID.class);
        Integer assignments = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM app_user_projects
            WHERE app_user_id = ? AND project_id = ? AND form_id = ? AND status = 'ACTIVE'
            """, Integer.class, appUser, TEST_PROJECT_ID, formId);
        if (assignments == null || assignments == 0) {
            jdbcTemplate.update("""
                    INSERT INTO app_user_projects
                    (id, app_user_id, project_id, form_id, assigned_by,
                     status, metadata, assigned_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE', '{}',
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, UUID.randomUUID(), appUser, TEST_PROJECT_ID, formId, owner);
        }
        return jdbcTemplate.queryForObject("""
            SELECT id FROM form_versions WHERE form_id = ? AND version = '1'
            """, UUID.class, formId);
    }

    @Test
    void m3FollowUpMayUseDifferentAssignedQuestionnaireButAmendmentMayNot() throws Exception {
        UUID targetVersion = m3SeparateFollowUpForm();
        String originalInstance = "uuid:" + UUID.randomUUID();
        var original = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("1", originalInstance, null, "Initial census form")), null);
        assertEquals(201, original.statusCode(), original.body());
        UUID originalId = jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE instance_id = ?", UUID.class, originalInstance);

        var illegalAmendment = sendM3("POST", projectBase + "/record-work-items",
            m3CreateWork(originalId, targetVersion, "AMENDMENT", null,
                "invalid-cross-form-" + UUID.randomUUID()), null);
        assertEquals(400, illegalAmendment.statusCode());

        String visitKey = "revisit-different-form-" + UUID.randomUUID();
        var visit = sendM3("POST", projectBase + "/record-work-items",
            m3CreateWork(originalId, targetVersion, "FOLLOW_UP", visitKey,
                "visit-" + UUID.randomUUID()), null);
        assertEquals(201, visit.statusCode(), visit.body());
        String taskId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(visit.body()).get("id").asText();

        String newInstance = "uuid:" + UUID.randomUUID();
        String xml = m3Xml("1", newInstance, null, "Periodic household observation")
            .replace(TEST_FORM_XMLNS, "m3_followup_form");
        var recordedVisit = sendM3("POST", projectBase + "/submission", multipartM3(xml), taskId);
        assertEquals(201, recordedVisit.statusCode(), recordedVisit.body());
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM record_links
            WHERE source_submission_id = ? AND link_type = 'FOLLOW_UP'
            """, Integer.class, originalId));
        assertNotEquals(
            jdbcTemplate.queryForObject("SELECT form_id FROM submissions WHERE id = ?",
                UUID.class, originalId),
            jdbcTemplate.queryForObject("SELECT form_id FROM submissions WHERE instance_id = ?",
                UUID.class, newInstance));
    }
}
