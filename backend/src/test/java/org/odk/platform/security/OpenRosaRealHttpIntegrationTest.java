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
    @Autowired private org.odk.platform.security.JwtService jwtService;
    @Autowired private org.springframework.security.core.userdetails.UserDetailsService userDetailsService;
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
        var preparedJson = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(preparation.body());
        assertEquals(SAMPLE_XFORM_XML, preparedJson.get("sourceXFormXml").asText(),
            "Historic source version must be the exact version-one XForm");
        assertNotEquals(preparedJson.get("sourceXFormXml").asText(),
            preparedJson.get("targetXFormXml").asText(),
            "Never substitute the current/target schema for the original");


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

        var timeline = sendM3("GET", projectBase + "/records/" + sourceId + "/timeline",
            null, null);
        assertEquals(200, timeline.statusCode(), timeline.body());
        assertTrue(timeline.body().contains(secondInstance));
        assertTrue(timeline.body().contains(visitKey));
        assertTrue(timeline.body().contains("FOLLOW_UP"));

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

    @Test
    void m3bCatalogLimitsAmendmentsToSourceFormAndVisitsToAssignedForms() throws Exception {
        UUID newerVersion = m3VersionTwo();
        UUID followUpVersion = m3SeparateFollowUpForm();
        String instance = "uuid:" + UUID.randomUUID();
        var initial = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("1", instance, null, "Version catalog owner")), null);
        assertEquals(201, initial.statusCode(), initial.body());
        UUID original = jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE instance_id = ?", UUID.class, instance);

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var amendment = sendM3("GET", projectBase + "/records/" + original
            + "/target-versions?workType=AMENDMENT", null, null);
        assertEquals(200, amendment.statusCode(), amendment.body());
        var amendmentArray = mapper.readTree(amendment.body());
        assertTrue(amendmentArray.isArray());
        assertTrue(amendmentArray.size() >= 2, amendment.body());
        boolean foundV2 = false;
        for (var item : amendmentArray) {
            assertTrue(item.get("sameFormAsSource").asBoolean());
            assertNotEquals(followUpVersion.toString(),
                item.get("targetFormVersionId").asText());
            assertNotNull(item.get("formTitle"));
            assertFalse(item.has("xmlContent"),
                "The catalog must never expose full XForm or submitted answers");
            if (newerVersion.toString().equals(item.get("targetFormVersionId").asText())) {
                foundV2 = true;
            }
        }
        assertTrue(foundV2, "Published newer version must be selectable for amendment");

        var followUp = sendM3("GET", projectBase + "/records/" + original
            + "/target-versions?workType=FOLLOW_UP", null, null);
        assertEquals(200, followUp.statusCode(), followUp.body());
        boolean foundOtherForm = false;
        for (var item : mapper.readTree(followUp.body())) {
            if (followUpVersion.toString().equals(item.get("targetFormVersionId").asText())) {
                foundOtherForm = true;
                assertFalse(item.get("sameFormAsSource").asBoolean());
            }
        }
        assertTrue(foundOtherForm, "Assigned alternate questionnaire is a valid follow-up");

        var noType = sendM3("GET", projectBase + "/records/" + original
            + "/target-versions", null, null);
        assertEquals(400, noType.statusCode(), noType.body());

        UUID otherUser = ensureAppUser("m3b_catalog_other", "synthetic-catalog-only");
        UUID form = jdbcTemplate.queryForObject(
            "SELECT form_id FROM submissions WHERE id = ?", UUID.class, original);
        ensureAppUserAssignment(otherUser, ensureFixtureOwner(), form);
        String otherAuth = "Basic " + Base64.getEncoder().encodeToString(
            "m3b_catalog_other:synthetic-catalog-only".getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(localUri(projectBase + "/records/"
                + original + "/target-versions?workType=AMENDMENT"))
            .header("Authorization", otherAuth).GET().build();
        var denied = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, denied.statusCode(),
            "An enumerator may not enumerate another user's eligible versions");

        // Revoking a target form assignment must remove its entries from this list.
        UUID appUser = jdbcTemplate.queryForObject(
            "SELECT id FROM app_users WHERE username = 'm2_synthetic_appuser'", UUID.class);
        UUID alternateForm = jdbcTemplate.queryForObject(
            "SELECT form_id FROM form_versions WHERE id = ?", UUID.class, followUpVersion);
        jdbcTemplate.update("""
            UPDATE app_user_projects SET status = 'INACTIVE'
            WHERE app_user_id = ? AND project_id = ? AND form_id = ?
            """, appUser, TEST_PROJECT_ID, alternateForm);
        try {
            var revoked = sendM3("GET", projectBase + "/records/" + original
                + "/target-versions?workType=FOLLOW_UP", null, null);
            assertEquals(200, revoked.statusCode(), revoked.body());
            assertFalse(revoked.body().contains(followUpVersion.toString()),
                "Unassigned target must disappear from candidate catalog");
        } finally {
            jdbcTemplate.update("""
                UPDATE app_user_projects SET status = 'ACTIVE'
                WHERE app_user_id = ? AND project_id = ? AND form_id = ?
                """, appUser, TEST_PROJECT_ID, alternateForm);
        }
    }

    @Test
    void m3cMissingLegacySourceSchemaIsExplicitNullWithoutLatestFallback() throws Exception {
        UUID targetVersion = m3VersionTwo();
        String instanceId = "uuid:" + UUID.randomUUID();
        var submission = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("1", instanceId, null, "Synthetic legacy orphan")), null);
        assertEquals(201, submission.statusCode(), submission.body());
        UUID original = jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE instance_id = ?", UUID.class, instanceId);

        // Simulate an imported legacy Kobo record whose historic form-version
        // association was unavailable. The source remains owned and readable.
        jdbcTemplate.update(
            "UPDATE submissions SET form_version_id = NULL WHERE id = ?", original);
        var created = sendM3("POST", projectBase + "/record-work-items",
            m3CreateWork(original, targetVersion, "AMENDMENT", null,
                "m3c-legacy-" + UUID.randomUUID()), null);
        assertEquals(201, created.statusCode(), created.body());
        String workId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(created.body()).get("id").asText();

        var prep = sendM3("GET", projectBase + "/record-work-items/" + workId
            + "/prepare", null, null);
        assertEquals(200, prep.statusCode(), prep.body());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(prep.body());
        assertTrue(json.get("sourceXFormXml").isNull(),
            "Unknown historic schema must be null, never replaced with target XForm");
        assertTrue(json.get("targetXFormXml").asText().contains("version=\"2\""));
        assertTrue(json.get("mappingApprovalRequired").asBoolean());
        assertEquals(instanceId, json.get("work").get("sourceInstanceId").asText());
    }

    @Test
    void m3UnknownFormVersionNeverFallsBackToLatest() throws Exception {
        String instance = "uuid:" + UUID.randomUUID();
        var response = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("9999", instance, null, "Never coerce form version")), null);
        assertEquals(400, response.statusCode(), response.body());
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM submissions WHERE instance_id = ?",
            Integer.class, instance));
    }

    @Test
    void m3OtherEnumeratorCannotDownloadOrAmendARecordTheyDoNotOwn() throws Exception {
        UUID targetVersion = m3VersionTwo();
        String firstInstance = "uuid:" + UUID.randomUUID();
        var initial = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("1", firstInstance, null, "Private household answers")), null);
        assertEquals(201, initial.statusCode());
        UUID sourceId = jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE instance_id = ?", UUID.class, firstInstance);
        UUID owner = ensureFixtureOwner();
        UUID otherUser = ensureAppUser("m3_other_enumerator", "synthetic-test-only-credential");
        UUID formId = jdbcTemplate.queryForObject(
            "SELECT id FROM xforms WHERE project_id = ? AND xmlns = ?",
            UUID.class, TEST_PROJECT_ID, TEST_FORM_XMLNS);
        ensureAppUserAssignment(otherUser, owner, formId);

        String otherAuth = "Basic " + Base64.getEncoder().encodeToString(
            "m3_other_enumerator:synthetic-test-only-credential".getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder prepare = HttpRequest.newBuilder(
            localUri(projectBase + "/records")).header("Authorization", otherAuth).GET();
        var records = client.send(prepare.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, records.statusCode());
        assertFalse(records.body().contains(firstInstance),
            "An assigned enumerator cannot enumerate a different enumerator's household records");

        String payload = m3CreateWork(sourceId, targetVersion, "AMENDMENT", null,
            "unauthorized-" + UUID.randomUUID());
        HttpRequest forbiddenCreate = HttpRequest.newBuilder(
                localUri(projectBase + "/record-work-items"))
            .header("Authorization", otherAuth)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
        var denial = client.send(forbiddenCreate, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, denial.statusCode(),
            "Records outside the operator's permitted ownership should not be disclosed");
    }
    @Test
    void m3d4aOwnedReadyAmendmentReturnsExactHashesButNeverGrantsTransfer() throws Exception {
        UUID targetVersion = m3VersionTwo();
        String originalInstance = "uuid:" + UUID.randomUUID();
        var initial = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("1", originalInstance, null,
                "M3D4A PRIVATE SYNTHETIC HOUSEHOLD ANSWER")), null);
        assertEquals(201, initial.statusCode(), initial.body());
        UUID sourceId = jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE instance_id = ?", UUID.class, originalInstance);
        var created = sendM3("POST", projectBase + "/record-work-items",
            m3CreateWork(sourceId, targetVersion, "AMENDMENT", null,
                "m3d4a-" + UUID.randomUUID()), null);
        assertEquals(201, created.statusCode(), created.body());
        String workId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(created.body()).get("id").asText();

        String url = projectBase + "/record-work-items/" + workId + "/typed-mapping-authority";
        var response = sendM3("GET", url, null, null);
        assertEquals(200, response.statusCode(), response.body());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
        assertEquals(workId, json.get("workId").asText());
        assertEquals(sourceId.toString(), json.get("sourceSubmissionId").asText());
        assertEquals(targetVersion.toString(), json.get("targetFormVersionId").asText());
        assertEquals("DENIED_TRUSTED_RULE_ISSUER_NOT_CONFIGURED", json.get("state").asText());
        assertEquals(0, json.get("approvedPaths").size());
        assertFalse(json.get("canReadHistoricalAnswers").asBoolean());
        assertFalse(json.get("canTransfer").asBoolean());
        assertFalse(json.get("canSubmit").asBoolean());
        assertTrue(json.get("sourceXmlSha256").asText().matches("[0-9a-f]{64}"));
        assertTrue(json.get("sourceXFormSha256").asText().matches("[0-9a-f]{64}"));
        assertTrue(json.get("targetXFormSha256").asText().matches("[0-9a-f]{64}"));
        assertFalse(response.body().contains("PRIVATE SYNTHETIC HOUSEHOLD ANSWER"),
            "Authority discovery must not expose raw source answers");
        assertFalse(response.body().contains("<data"),
            "Authority discovery must not expose raw XML or XForms");

        var noAuth = send("GET", localUri(url), false, null, null);
        assertEquals(401, noAuth.statusCode());

        // No enumerator-controlled grant issuance API exists in 3B-4A.
        var unauthorizedIssue = sendM3("POST", url, "{\"approvedPaths\":[\"/data/name\"]}", null);
        assertTrue(unauthorizedIssue.statusCode() == 405 ||
            unauthorizedIssue.statusCode() == 404,
            "A normal app account must not be able to mint mapping grants");
    }

    @Test
    void m3d4aFollowUpAndMissingHistoricalSchemaAreNeverAuthorized() throws Exception {
        UUID targetVersion = m3VersionTwo();
        String sourceInstance = "uuid:" + UUID.randomUUID();
        var initial = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("1", sourceInstance, null, "Independent source")), null);
        assertEquals(201, initial.statusCode(), initial.body());
        UUID sourceId = jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE instance_id = ?", UUID.class, sourceInstance);

        var followUp = sendM3("POST", projectBase + "/record-work-items",
            m3CreateWork(sourceId, targetVersion, "FOLLOW_UP",
                "visit-" + UUID.randomUUID(), "m3d4a-visit-" + UUID.randomUUID()), null);
        assertEquals(201, followUp.statusCode(), followUp.body());
        String followId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(followUp.body()).get("id").asText();
        var deniedVisit = sendM3("GET", projectBase + "/record-work-items/" +
            followId + "/typed-mapping-authority", null, null);
        assertEquals(200, deniedVisit.statusCode(), deniedVisit.body());
        var visitJson = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(deniedVisit.body());
        assertEquals("DENIED_INDEPENDENT_VISIT", visitJson.get("state").asText());
        assertEquals(0, visitJson.get("approvedPaths").size());

        // Simulate a separate imported legacy record without a known original
        // form version. This test never changes production data.
        String unknownVersionInstance = "uuid:" + UUID.randomUUID();
        var legacy = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("1", unknownVersionInstance, null, "Legacy record")), null);
        assertEquals(201, legacy.statusCode(), legacy.body());
        UUID legacyId = jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE instance_id = ?",
            UUID.class, unknownVersionInstance);
        jdbcTemplate.update("UPDATE submissions SET form_version_id = NULL WHERE id = ?",
            legacyId);
        var amendment = sendM3("POST", projectBase + "/record-work-items",
            m3CreateWork(legacyId, targetVersion, "AMENDMENT", null,
                "m3d4a-legacy-" + UUID.randomUUID()), null);
        assertEquals(201, amendment.statusCode(), amendment.body());
        String amendmentId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(amendment.body()).get("id").asText();
        var deniedLegacy = sendM3("GET", projectBase + "/record-work-items/" +
            amendmentId + "/typed-mapping-authority", null, null);
        assertEquals(200, deniedLegacy.statusCode(), deniedLegacy.body());
        var legacyJson = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(deniedLegacy.body());
        assertEquals("DENIED_HISTORICAL_SCHEMA_UNAVAILABLE",
            legacyJson.get("state").asText());
        assertTrue(legacyJson.get("sourceXFormSha256").isNull());
        assertFalse(legacyJson.get("canTransfer").asBoolean());
    }

    @Test
    void m3d4aUnauthorizedOrMissingWorkDoesNotDiscloseMappingMetadata() throws Exception {
        var missing = sendM3("GET", projectBase + "/record-work-items/" +
            UUID.randomUUID() + "/typed-mapping-authority", null, null);
        assertEquals(404, missing.statusCode());

        UUID targetVersion = m3VersionTwo();
        String originalInstance = "uuid:" + UUID.randomUUID();
        var initial = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("1", originalInstance, null, "Private mapping")), null);
        assertEquals(201, initial.statusCode(), initial.body());
        UUID sourceId = jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE instance_id = ?", UUID.class, originalInstance);
        var work = sendM3("POST", projectBase + "/record-work-items",
            m3CreateWork(sourceId, targetVersion, "AMENDMENT", null,
                "m3d4a-owner-" + UUID.randomUUID()), null);
        assertEquals(201, work.statusCode(), work.body());
        String workId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(work.body()).get("id").asText();

        UUID other = ensureAppUser("m3d4a_other_enumerator", "synthetic-credential-only");
        UUID owner = ensureFixtureOwner();
        UUID form = jdbcTemplate.queryForObject(
            "SELECT id FROM xforms WHERE project_id = ? AND xmlns = ?",
            UUID.class, TEST_PROJECT_ID, TEST_FORM_XMLNS);
        ensureAppUserAssignment(other, owner, form);
        String alternateAuth = "Basic " + Base64.getEncoder().encodeToString(
            "m3d4a_other_enumerator:synthetic-credential-only"
                .getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(localUri(projectBase +
                "/record-work-items/" + workId + "/typed-mapping-authority"))
            .header("Authorization", alternateAuth).GET().build();
        var forbidden = client.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(404, forbidden.statusCode());
        assertFalse(forbidden.body().contains("sourceXmlSha256"));
    }

    private String m3d4bReviewerUrl(String workId) {
        return "http://127.0.0.1:" + port + "/api/projects/" + TEST_PROJECT_ID
            + "/record-work-items/" + workId + "/typed-review-eligibility";
    }

    private String m3d4bReviewerToken() {
        UUID owner = ensureFixtureOwner();
        String reviewer = "m3d4b_independent_web_reviewer";
        String email = "m3d4b-reviewer@example.test";
        List<UUID> known = jdbcTemplate.query(
            "SELECT id FROM users WHERE username = ?",
            (rs, idx) -> rs.getObject("id", UUID.class), reviewer);
        UUID reviewerId = known.isEmpty() ? UUID.randomUUID() : known.get(0);
        if (known.isEmpty()) {
            jdbcTemplate.update("""
                INSERT INTO users
                  (id, username, email, password_hash, first_name, last_name,
                   is_active, is_verified, failed_login_attempts,
                   created_at, updated_at)
                VALUES (?, ?, ?, ?, 'Independent', 'Reviewer',
                        TRUE, TRUE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, reviewerId, reviewer, email, passwordEncoder.encode(
                    "reviewer-test-fixture-only-password"));
        } else {
            jdbcTemplate.update("""
                UPDATE users SET is_active = TRUE, is_verified = TRUE,
                    email = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, email, reviewerId);
        }
        List<UUID> roleIds = jdbcTemplate.query(
            "SELECT id FROM roles WHERE name = 'PROJECT_MANAGER'",
            (rs, idx) -> rs.getObject("id", UUID.class));
        UUID roleId = roleIds.isEmpty() ? UUID.randomUUID() : roleIds.get(0);
        if (roleIds.isEmpty()) {
            jdbcTemplate.update("""
                INSERT INTO roles (id, name, display_name, is_system, is_active,
                                   created_at, updated_at)
                VALUES (?, 'PROJECT_MANAGER', 'Project manager', TRUE, TRUE,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, roleId);
        }
        List<UUID> memberships = jdbcTemplate.query("""
                SELECT id FROM project_members
                WHERE project_id = ? AND user_id = ? AND role_id = ?
                """, (rs, idx) -> rs.getObject("id", UUID.class),
                TEST_PROJECT_ID, reviewerId, roleId);
        if (memberships.isEmpty()) {
            jdbcTemplate.update("""
                INSERT INTO project_members
                (id, project_id, user_id, role_id, assigned_by, is_active,
                 created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), TEST_PROJECT_ID, reviewerId, roleId, owner);
        } else {
            jdbcTemplate.update("""
                UPDATE project_members SET is_active = TRUE, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, memberships.get(0));
        }
        // The existing UserRepository JWT lookup INNER JOIN FETCHes both
        // global user_roles and role_permissions. Supply a harmless synthetic
        // login role+permission as fixture data, separate from the authoritative
        // project_members PROJECT_MANAGER reviewer eligibility check.
        String authRole = "M3D4B1_TEST_LOGIN_ROLE";
        List<UUID> authRoles = jdbcTemplate.query(
            "SELECT id FROM roles WHERE name = ?",
            (rs, idx) -> rs.getObject("id", UUID.class), authRole);
        UUID authRoleId = authRoles.isEmpty() ? UUID.randomUUID() : authRoles.get(0);
        if (authRoles.isEmpty()) {
            jdbcTemplate.update("""
                INSERT INTO roles
                (id, name, display_name, is_system, is_active, created_at, updated_at)
                VALUES (?, ?, 'Synthetic test login role', FALSE, TRUE,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, authRoleId, authRole);
        }
        String authPermission = "M3D4B1_TEST_LOGIN_PERMISSION";
        List<UUID> permissions = jdbcTemplate.query(
            "SELECT id FROM permissions WHERE name = ?",
            (rs, idx) -> rs.getObject("id", UUID.class), authPermission);
        UUID permissionId = permissions.isEmpty() ? UUID.randomUUID() : permissions.get(0);
        if (permissions.isEmpty()) {
            jdbcTemplate.update("""
                INSERT INTO permissions
                (id, name, display_name, resource, action, is_system, is_active,
                 created_at, updated_at)
                VALUES (?, ?, 'Synthetic login fixture', 'test', 'read', FALSE,
                        TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, permissionId, authPermission);
        }
        Integer permissionAssigned = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM role_permissions WHERE role_id = ? AND permission_id = ?
            """, Integer.class, authRoleId, permissionId);
        if (permissionAssigned == 0) {
            jdbcTemplate.update("INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                authRoleId, permissionId);
        }
        Integer globalRoleAssigned = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND role_id = ?
                AND project_id IS NULL
            """, Integer.class, reviewerId, authRoleId);
        if (globalRoleAssigned == 0) {
            jdbcTemplate.update("""
                INSERT INTO user_roles (id, user_id, role_id, project_id, assigned_by)
                VALUES (?, ?, ?, NULL, ?)
                """, UUID.randomUUID(), reviewerId, authRoleId, owner);
        }
        // Signed by this isolated integration test's actual JWT service. The
        // request still traverses the production JWT filter and database policy.
        return "Bearer " + jwtService.generateToken(
            userDetailsService.loadUserByUsername(reviewer));
    }

    private HttpResponse<String> m3d4bReviewerGet(String url, String bearer)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(localUri(url))
            .timeout(Duration.ofSeconds(20)).GET();
        if (bearer != null) request.header("Authorization", bearer);
        return client.send(request.build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void m3d4bWebReviewerEligibilityUsesCurrentProjectMembershipNotMobileLogin() throws Exception {
        UUID targetVersion = m3VersionTwo();
        String sourceInstance = "uuid:" + UUID.randomUUID();
        var first = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("1", sourceInstance, null, "Reviewer fixture")), null);
        assertEquals(201, first.statusCode(), first.body());
        UUID sourceId = jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE instance_id = ?", UUID.class, sourceInstance);
        var created = sendM3("POST", projectBase + "/record-work-items",
            m3CreateWork(sourceId, targetVersion, "AMENDMENT", null,
                "m3d4b-review-" + UUID.randomUUID()), null);
        assertEquals(201, created.statusCode(), created.body());
        String workId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(created.body()).get("id").asText();
        String url = m3d4bReviewerUrl(workId);

        // No public anonymous or mobile Basic endpoint can mint or even
        // request independent reviewer eligibility.
        var anonymous = m3d4bReviewerGet(url, null);
        assertTrue(anonymous.statusCode() == 401 || anonymous.statusCode() == 403);
        var enumerator = m3d4bReviewerGet(url, authorization);
        assertTrue(enumerator.statusCode() == 401 || enumerator.statusCode() == 403);

        String reviewerBearer = m3d4bReviewerToken();
        var eligible = m3d4bReviewerGet(url, reviewerBearer);
        assertEquals(200, eligible.statusCode(), eligible.body());
        var data = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(eligible.body());
        assertEquals("ELIGIBLE_TO_REQUEST_REVIEW_ONLY", data.get("state").asText());
        assertTrue(data.get("eligibleToRequestIndependentReview").asBoolean());
        assertFalse(data.get("canApproveMapping").asBoolean());
        assertFalse(data.get("canTransfer").asBoolean());
        assertFalse(data.get("canSubmit").asBoolean());
        assertFalse(eligible.body().contains("Reviewer fixture"));
        assertFalse(eligible.body().contains("<data"));

        UUID reviewerId = jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE username = 'm3d4b_independent_web_reviewer'",
            UUID.class);
        try {
            jdbcTemplate.update("""
                UPDATE project_members SET is_active = FALSE
                WHERE project_id = ? AND user_id = ?
                """, TEST_PROJECT_ID, reviewerId);
            var revoked = m3d4bReviewerGet(url, reviewerBearer);
            assertEquals(404, revoked.statusCode(),
                "A still-valid JWT must not survive project-role revocation");
        } finally {
            jdbcTemplate.update("""
                UPDATE project_members SET is_active = TRUE
                WHERE project_id = ? AND user_id = ?
                """, TEST_PROJECT_ID, reviewerId);
        }

        // No POST approval endpoint is introduced by this slice.
        var issue = HttpRequest.newBuilder(localUri(url))
            .header("Authorization", reviewerBearer)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"approvedPaths\":[\"/data/name\"]}"))
            .build();
        var issueResponse = client.send(issue,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertTrue(issueResponse.statusCode() == 404 ||
            issueResponse.statusCode() == 405);
    }

    @Test
    void m3d4bFollowUpIsNotEligibleForHistoricalMappingReview() throws Exception {
        UUID targetVersion = m3VersionTwo();
        String sourceInstance = "uuid:" + UUID.randomUUID();
        var first = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("1", sourceInstance, null, "Independent visit")), null);
        assertEquals(201, first.statusCode(), first.body());
        UUID sourceId = jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE instance_id = ?", UUID.class, sourceInstance);
        var created = sendM3("POST", projectBase + "/record-work-items",
            m3CreateWork(sourceId, targetVersion, "FOLLOW_UP",
                "visit-" + UUID.randomUUID(), "m3d4b-follow-" + UUID.randomUUID()), null);
        assertEquals(201, created.statusCode(), created.body());
        String workId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(created.body()).get("id").asText();
        String token = m3d4bReviewerToken();
        var response = m3d4bReviewerGet(m3d4bReviewerUrl(workId), token);
        assertEquals(404, response.statusCode());
    }

    private String m3d4b2Endpoint(String workId) {
        return "http://127.0.0.1:" + port + "/api/projects/" + TEST_PROJECT_ID +
            "/record-work-items/" + workId + "/typed-review-recommendations";
    }

    private HttpResponse<String> m3d4b2Post(
            String endpoint, String auth, String body) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(localUri(endpoint))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json");
        if (auth != null) req.header("Authorization", auth);
        return client.send(req.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode m3d4b2Request(
            com.fasterxml.jackson.databind.JsonNode ctx, String key, String reason,
            String path, String disposition) {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        json.put("clientRequestId", key);
        json.put("reasonCode", reason);
        json.putObject("fieldDecisions").put(path, disposition);
        json.put("sourceXmlSha256", ctx.get("sourceXmlSha256").asText());
        json.put("sourceXFormSha256", ctx.get("sourceXFormSha256").asText());
        json.put("targetXFormSha256", ctx.get("targetXFormSha256").asText());
        return json;
    }

    @Test
    void m3d4b2ReviewerRecommendationsAreImmutableAndDoNotGrantTransfers() throws Exception {
        UUID targetVersion = m3VersionTwo();
        String originalInstance = "uuid:" + UUID.randomUUID();
        var first = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("1", originalInstance, null,
                "M3D4B2 SYNTHETIC PRIVATE HOUSEHOLD ANSWER")), null);
        assertEquals(201, first.statusCode(), first.body());
        UUID original = jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE instance_id = ?",
            UUID.class, originalInstance);
        var created = sendM3("POST", projectBase + "/record-work-items",
            m3CreateWork(original, targetVersion, "AMENDMENT", null,
                "m3d4b2-" + UUID.randomUUID()), null);
        assertEquals(201, created.statusCode(), created.body());
        String workId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(created.body()).get("id").asText();
        String endpoint = m3d4b2Endpoint(workId);
        String reviewerToken = m3d4bReviewerToken();

        var contextResponse = m3d4bReviewerGet(endpoint + "/context", reviewerToken);
        assertEquals(200, contextResponse.statusCode(), contextResponse.body());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var ctx = mapper.readTree(contextResponse.body());
        assertEquals("REVIEW_RECOMMENDATION_ONLY", ctx.get("state").asText());
        assertEquals(originalInstance, ctx.get("sourceInstanceId").asText());
        assertTrue(ctx.get("sourceXmlSha256").asText().matches("[0-9a-f]{64}"));
        assertTrue(ctx.get("sourceXFormSha256").asText().matches("[0-9a-f]{64}"));
        assertTrue(ctx.get("targetXFormSha256").asText().matches("[0-9a-f]{64}"));
        assertFalse(ctx.get("canApproveMapping").asBoolean());
        assertFalse(ctx.get("canTransfer").asBoolean());
        assertFalse(ctx.get("canSubmit").asBoolean());
        assertFalse(contextResponse.body().contains("SYNTHETIC PRIVATE HOUSEHOLD ANSWER"));
        assertFalse(contextResponse.body().contains("<data"));

        String key = "review-" + UUID.randomUUID();
        var body = m3d4b2Request(ctx, key, "EXPLICIT_RULE_REVIEW_NEEDED",
            "/data/name", "ESCALATE_FOR_RULE_REVIEW");
        var recorded = m3d4b2Post(endpoint, reviewerToken, body.toString());
        assertEquals(201, recorded.statusCode(), recorded.body());
        var receipt = mapper.readTree(recorded.body());
        assertEquals("RECORDED_NON_AUTHORITATIVE", receipt.get("state").asText());
        assertEquals(1, receipt.get("reviewedPathCount").asInt());
        assertFalse(receipt.get("canApproveMapping").asBoolean());
        assertFalse(receipt.get("canReadHistoricalAnswers").asBoolean());
        assertFalse(receipt.get("canTransfer").asBoolean());
        assertFalse(receipt.get("canSubmit").asBoolean());
        assertTrue(receipt.get("evidenceSha256").asText().matches("[0-9a-f]{64}"));
        assertFalse(recorded.body().contains("SYNTHETIC PRIVATE HOUSEHOLD ANSWER"));

        var replay = m3d4b2Post(endpoint, reviewerToken, body.toString());
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(receipt.get("recommendationId").asText(),
            mapper.readTree(replay.body()).get("recommendationId").asText());
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM typed_mapping_review_recommendations
            WHERE work_item_id = ? AND client_request_id = ?
            """, Integer.class, UUID.fromString(workId), key));
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM typed_mapping_review_fields f
            JOIN typed_mapping_review_recommendations r ON r.id = f.recommendation_id
            WHERE r.work_item_id = ? AND f.target_path = '/data/name'
              AND f.disposition = 'ESCALATE_FOR_RULE_REVIEW'
            """, Integer.class, UUID.fromString(workId)));

        var changed = m3d4b2Request(ctx, key, "DO_NOT_TRANSFER",
            "/data/name", "LEAVE_EMPTY");
        var conflict = m3d4b2Post(endpoint, reviewerToken, changed.toString());
        assertEquals(409, conflict.statusCode(), conflict.body());
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM typed_mapping_review_recommendations
            WHERE work_item_id = ?
            """, Integer.class, UUID.fromString(workId)));

        var drift = m3d4b2Request(ctx, "stale-" + UUID.randomUUID(),
            "REENTER_REQUIRED", "/data/name", "REENTER");
        drift.put("sourceXmlSha256", "f".repeat(64));
        var rejected = m3d4b2Post(endpoint, reviewerToken, drift.toString());
        assertEquals(409, rejected.statusCode(), rejected.body());
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM typed_mapping_review_recommendations
            WHERE work_item_id = ?
            """, Integer.class, UUID.fromString(workId)));

        // A reviewer can add a few append-only corrections, but cannot
        // exhaust the database by rotating valid idempotency request keys.
        for (int n = 0; n < 9; n++) {
            var additional = m3d4b2Request(ctx,
                "bounded-" + UUID.randomUUID() + "-" + n,
                "REENTER_REQUIRED", "/data/name", "REENTER");
            var stored = m3d4b2Post(endpoint, reviewerToken, additional.toString());
            assertEquals(201, stored.statusCode(), stored.body());
        }
        var overflow = m3d4b2Request(ctx,
            "overflow-" + UUID.randomUUID(),
            "REENTER_REQUIRED", "/data/name", "REENTER");
        var tooMany = m3d4b2Post(endpoint, reviewerToken, overflow.toString());
        assertEquals(409, tooMany.statusCode(), tooMany.body());
        assertEquals(10, jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM typed_mapping_review_recommendations
            WHERE work_item_id = ?
            """, Integer.class, UUID.fromString(workId)));
    }

    @Test
    void m3d4b2RejectsUnknownPathsAndRevokedReviewerWithoutWritingEvidence()
            throws Exception {
        UUID targetVersion = m3VersionTwo();
        String originalInstance = "uuid:" + UUID.randomUUID();
        var first = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("1", originalInstance, null, "Review restrictions")), null);
        assertEquals(201, first.statusCode(), first.body());
        UUID sourceId = jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE instance_id = ?",
            UUID.class, originalInstance);
        var created = sendM3("POST", projectBase + "/record-work-items",
            m3CreateWork(sourceId, targetVersion, "AMENDMENT", null,
                "m3d4b2-deny-" + UUID.randomUUID()), null);
        assertEquals(201, created.statusCode(), created.body());
        String workId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(created.body()).get("id").asText();
        String endpoint = m3d4b2Endpoint(workId);
        String token = m3d4bReviewerToken();
        var ctxResponse = m3d4bReviewerGet(endpoint + "/context", token);
        assertEquals(200, ctxResponse.statusCode(), ctxResponse.body());
        var ctx = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(ctxResponse.body());
        String requestKey = "deny-" + UUID.randomUUID();

        var unsafe = m3d4b2Request(ctx, requestKey,
            "REENTER_REQUIRED", "/data/../private", "REENTER");
        var invalid = m3d4b2Post(endpoint, token, unsafe.toString());
        assertEquals(400, invalid.statusCode());
        var fakeRule = m3d4b2Request(ctx, requestKey,
            "EXPLICIT_RULE_REVIEW_NEEDED", "/data/name", "APPROVE_TRANSFER");
        var forbiddenRule = m3d4b2Post(endpoint, token, fakeRule.toString());
        assertEquals(400, forbiddenRule.statusCode());
        var unauthorized = m3d4b2Post(endpoint, authorization,
            m3d4b2Request(ctx, requestKey, "REENTER_REQUIRED",
                "/data/name", "REENTER").toString());
        assertTrue(unauthorized.statusCode() == 401 ||
            unauthorized.statusCode() == 403);
        var noToken = m3d4b2Post(endpoint, null,
            m3d4b2Request(ctx, requestKey, "REENTER_REQUIRED",
                "/data/name", "REENTER").toString());
        assertTrue(noToken.statusCode() == 401 || noToken.statusCode() == 403);

        UUID reviewerId = jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE username = 'm3d4b_independent_web_reviewer'",
            UUID.class);
        try {
            jdbcTemplate.update("""
                UPDATE project_members SET is_active = FALSE
                WHERE project_id = ? AND user_id = ?
                """, TEST_PROJECT_ID, reviewerId);
            var revoked = m3d4b2Post(endpoint, token,
                m3d4b2Request(ctx, requestKey, "REENTER_REQUIRED",
                    "/data/name", "REENTER").toString());
            assertEquals(404, revoked.statusCode());
        } finally {
            jdbcTemplate.update("""
                UPDATE project_members SET is_active = TRUE
                WHERE project_id = ? AND user_id = ?
                """, TEST_PROJECT_ID, reviewerId);
        }
        assertEquals(0, jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM typed_mapping_review_recommendations
            WHERE work_item_id = ?
            """, Integer.class, UUID.fromString(workId)));

        var visit = sendM3("POST", projectBase + "/record-work-items",
            m3CreateWork(sourceId, targetVersion, "FOLLOW_UP",
                "visit-" + UUID.randomUUID(), "m3d4b2-follow-" + UUID.randomUUID()), null);
        assertEquals(201, visit.statusCode(), visit.body());
        String visitId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(visit.body()).get("id").asText();
        var followContext = m3d4bReviewerGet(
            m3d4b2Endpoint(visitId) + "/context", token);
        assertEquals(404, followContext.statusCode());
    }

    private String m3d4b2bFinalSignerToken() {
        // Creates the benign JWT-login role+permission from previous synthetic
        // fixture, which is distinct from project-admin reviewer authorization.
        m3d4bReviewerToken();
        String name = "m3d4b2b_final_web_authorizer";
        String email = "m3d4b2b-final-signer@example.test";
        UUID owner = ensureFixtureOwner();
        List<UUID> known = jdbcTemplate.query(
            "SELECT id FROM users WHERE username = ?",
            (rs, idx) -> rs.getObject("id", UUID.class), name);
        UUID signer = known.isEmpty() ? UUID.randomUUID() : known.get(0);
        if (known.isEmpty()) {
            jdbcTemplate.update("""
                INSERT INTO users (id, username, email, password_hash,
                   first_name, last_name, is_active, is_verified,
                   failed_login_attempts, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'Final', 'Authorizer', TRUE, TRUE, 0,
                   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, signer, name, email,
                passwordEncoder.encode("final-signer-synthetic-test-only"));
        } else {
            jdbcTemplate.update("""
                UPDATE users SET is_active = TRUE, is_verified = TRUE,
                    email = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, email, signer);
        }
        UUID role = jdbcTemplate.query("""
                SELECT id FROM roles WHERE name = 'M3D4B1_TEST_LOGIN_ROLE'
                """, (rs, idx) -> rs.getObject("id", UUID.class)).get(0);
        Integer hasLoginRole = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM user_roles
                WHERE user_id = ? AND role_id = ? AND project_id IS NULL
                """, Integer.class, signer, role);
        if (hasLoginRole == null || hasLoginRole == 0) {
            jdbcTemplate.update("""
                INSERT INTO user_roles (id, user_id, role_id, assigned_by)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), signer, role, owner);
        }
        List<UUID> projectAdminRoles = jdbcTemplate.query("""
                SELECT id FROM roles WHERE name = 'PROJECT_ADMIN'
                """, (rs, idx) -> rs.getObject("id", UUID.class));
        UUID adminRole = projectAdminRoles.isEmpty() ?
            UUID.randomUUID() : projectAdminRoles.get(0);
        if (projectAdminRoles.isEmpty()) {
            jdbcTemplate.update("""
                INSERT INTO roles (id, name, display_name, is_system, is_active,
                                   created_at, updated_at)
                VALUES (?, 'PROJECT_ADMIN', 'Project administrator', TRUE, TRUE,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, adminRole);
        }
        var memberships = jdbcTemplate.query("""
                SELECT id FROM project_members
                WHERE project_id = ? AND user_id = ? AND role_id = ?
                """, (rs, idx) -> rs.getObject("id", UUID.class),
                TEST_PROJECT_ID, signer, adminRole);
        if (memberships.isEmpty()) {
            jdbcTemplate.update("""
                INSERT INTO project_members
                  (id, project_id, user_id, role_id, assigned_by,
                   is_active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, TRUE,
                   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), TEST_PROJECT_ID, signer, adminRole, owner);
        } else {
            jdbcTemplate.update("""
                UPDATE project_members SET is_active = TRUE,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, memberships.get(0));
        }
        return "Bearer " + jwtService.generateToken(
            userDetailsService.loadUserByUsername(name));
    }

    private record M3D4B2BFixture(
        String workId, String recommendationId,
        String recommendationSha256, String originalInstanceId,
        String reviewerToken, String signerToken) { }

    private M3D4B2BFixture m3d4b2bFixture() throws Exception {
        UUID targetVersion = m3VersionTwo();
        String sourceInstance = "uuid:" + UUID.randomUUID();
        var first = sendM3("POST", projectBase + "/submission",
            multipartM3(m3Xml("1", sourceInstance, null,
                "M3D4B2B NONDISCLOSABLE TEST ANSWER")), null);
        assertEquals(201, first.statusCode(), first.body());
        UUID original = jdbcTemplate.queryForObject(
            "SELECT id FROM submissions WHERE instance_id = ?",
            UUID.class, sourceInstance);
        var created = sendM3("POST", projectBase + "/record-work-items",
            m3CreateWork(original, targetVersion, "AMENDMENT", null,
                "m3d4b2b-" + UUID.randomUUID()), null);
        assertEquals(201, created.statusCode(), created.body());
        String workId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(created.body()).get("id").asText();
        String reviewerToken = m3d4bReviewerToken();
        String signerToken = m3d4b2bFinalSignerToken();
        var contextResponse = m3d4bReviewerGet(
            m3d4b2Endpoint(workId) + "/context", reviewerToken);
        assertEquals(200, contextResponse.statusCode(), contextResponse.body());
        var ctx = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(contextResponse.body());
        var recommendation = m3d4b2Request(ctx,
            "m3d4b2b-recommend-" + UUID.randomUUID(), "EXPLICIT_RULE_REVIEW_NEEDED",
            "/data/name", "ESCALATE_FOR_RULE_REVIEW");
        var recorded = m3d4b2Post(
            m3d4b2Endpoint(workId), reviewerToken, recommendation.toString());
        assertEquals(201, recorded.statusCode(), recorded.body());
        var evidence = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(recorded.body());
        return new M3D4B2BFixture(workId,
            evidence.get("recommendationId").asText(),
            evidence.get("evidenceSha256").asText(), sourceInstance,
            reviewerToken, signerToken);
    }

    private String m3d4b2bUrl(M3D4B2BFixture f) {
        return "http://127.0.0.1:" + port + "/api/projects/" +
            TEST_PROJECT_ID + "/record-work-items/" + f.workId() +
            "/typed-review-final-denials/" + f.recommendationId();
    }

    @Test
    void m3d4b2bIndependentFinalAdminDenialIsIdempotentAndCannotGrant() throws Exception {
        M3D4B2BFixture f = m3d4b2bFixture();
        String endpoint = m3d4b2bUrl(f);
        var eligibility = m3d4bReviewerGet(
            endpoint + "/eligibility", f.signerToken());
        assertEquals(200, eligibility.statusCode(), eligibility.body());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var eligible = mapper.readTree(eligibility.body());
        assertEquals("ELIGIBLE_TO_DENY_ONLY", eligible.get("state").asText());
        assertEquals(f.recommendationSha256(),
            eligible.get("recommendationEvidenceSha256").asText());
        assertNotEquals(eligible.get("originalReviewerId").asText(),
            eligible.get("finalAuthorizerId").asText());
        assertFalse(eligible.get("canApproveMapping").asBoolean());
        assertFalse(eligible.get("canTransfer").asBoolean());
        assertFalse(eligible.get("canSubmit").asBoolean());
        assertFalse(eligibility.body().contains("NONDISCLOSABLE TEST ANSWER"));
        assertFalse(eligibility.body().contains("<data"));

        var payload = mapper.createObjectNode();
        payload.put("clientRequestId", "deny-" + UUID.randomUUID());
        payload.put("reasonCode", "SCHEMA_UNSAFE");
        payload.put("recommendationEvidenceSha256", f.recommendationSha256());
        var denied = m3d4b2Post(endpoint, f.signerToken(), payload.toString());
        assertEquals(201, denied.statusCode(), denied.body());
        var receipt = mapper.readTree(denied.body());
        assertEquals("DENIED_NO_MAPPING_GRANT", receipt.get("state").asText());
        assertFalse(receipt.get("canApproveMapping").asBoolean());
        assertFalse(receipt.get("canReadHistoricalAnswers").asBoolean());
        assertFalse(receipt.get("canTransfer").asBoolean());
        assertFalse(receipt.get("canSubmit").asBoolean());
        assertTrue(receipt.get("denialEvidenceSha256").asText().matches("[0-9a-f]{64}"));

        var replay = m3d4b2Post(endpoint, f.signerToken(), payload.toString());
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(receipt.get("denialId").asText(),
            mapper.readTree(replay.body()).get("denialId").asText());

        var changed = payload.deepCopy();
        changed.put("reasonCode", "CONSENT_UNCLEAR");
        assertEquals(409, m3d4b2Post(
            endpoint, f.signerToken(), changed.toString()).statusCode());
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM typed_mapping_final_denials
            WHERE work_item_id = ?
            """, Integer.class, UUID.fromString(f.workId())));
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM typed_mapping_review_recommendations
            WHERE id = ?
            """, Integer.class, UUID.fromString(f.recommendationId())));
    }

    @Test
    void m3d4b2bSelfDenialAndMobilePrincipalCannotBecomeFinalAuthorizer()
            throws Exception {
        M3D4B2BFixture f = m3d4b2bFixture();
        String endpoint = m3d4b2bUrl(f);
        var reviewerAsSigner = m3d4bReviewerGet(
            endpoint + "/eligibility", f.reviewerToken());
        assertTrue(reviewerAsSigner.statusCode() == 404 ||
            reviewerAsSigner.statusCode() == 409);
        var enumerator = m3d4bReviewerGet(endpoint + "/eligibility", authorization);
        assertTrue(enumerator.statusCode() == 401 ||
            enumerator.statusCode() == 403);
        var anonymous = m3d4bReviewerGet(endpoint + "/eligibility", null);
        assertTrue(anonymous.statusCode() == 401 ||
            anonymous.statusCode() == 403);

        // The signer qualifies as a project admin, but cannot deny a
        // recommendation that the same signer wrote under their own JWT.
        var context = m3d4bReviewerGet(
            m3d4b2Endpoint(f.workId()) + "/context", f.signerToken());
        assertEquals(200, context.statusCode(), context.body());
        var ctx = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(context.body());
        var ownRecommendation = m3d4b2Post(m3d4b2Endpoint(f.workId()),
            f.signerToken(), m3d4b2Request(ctx,
                "signer-written-" + UUID.randomUUID(), "REENTER_REQUIRED",
                "/data/name", "REENTER").toString());
        assertEquals(201, ownRecommendation.statusCode(), ownRecommendation.body());
        var own = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(ownRecommendation.body());
        String ownUrl = endpoint.substring(0, endpoint.lastIndexOf('/') + 1) +
            own.get("recommendationId").asText() + "/eligibility";
        var cannotSignOwn = m3d4bReviewerGet(ownUrl, f.signerToken());
        assertEquals(409, cannotSignOwn.statusCode());
        assertEquals(0, jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM typed_mapping_final_denials
            WHERE work_item_id = ?
            """, Integer.class, UUID.fromString(f.workId())));
    }

    @Test
    void m3d4b2bRevokedFinalAuthorizerAndChangedEvidenceFailClosed()
            throws Exception {
        M3D4B2BFixture f = m3d4b2bFixture();
        String endpoint = m3d4b2bUrl(f);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var payload = mapper.createObjectNode();
        payload.put("clientRequestId", "reject-" + UUID.randomUUID());
        payload.put("reasonCode", "POLICY_NOT_SATISFIED");
        payload.put("recommendationEvidenceSha256", "f".repeat(64));
        assertEquals(409, m3d4b2Post(endpoint,
            f.signerToken(), payload.toString()).statusCode());
        payload.put("recommendationEvidenceSha256", f.recommendationSha256());
        payload.put("reasonCode", "APPROVE_TRANSFER");
        assertEquals(400, m3d4b2Post(endpoint,
            f.signerToken(), payload.toString()).statusCode());
        payload.put("reasonCode", "POLICY_NOT_SATISFIED");

        UUID signer = jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE username = 'm3d4b2b_final_web_authorizer'",
            UUID.class);
        try {
            jdbcTemplate.update("""
                UPDATE project_members SET is_active = FALSE
                WHERE project_id = ? AND user_id = ?
                """, TEST_PROJECT_ID, signer);
            assertEquals(404, m3d4b2Post(
                endpoint, f.signerToken(), payload.toString()).statusCode());
        } finally {
            jdbcTemplate.update("""
                UPDATE project_members SET is_active = TRUE
                WHERE project_id = ? AND user_id = ?
                """, TEST_PROJECT_ID, signer);
        }
        assertEquals(0, jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM typed_mapping_final_denials
            WHERE work_item_id = ?
            """, Integer.class, UUID.fromString(f.workId())));
    }

    private String m3d4b2b2aPolicyUrl(M3D4B2BFixture fixture) {
        return "http://127.0.0.1:" + port + "/api/projects/" +
            TEST_PROJECT_ID + "/record-work-items/" + fixture.workId() +
            "/typed-rule-candidates/" + fixture.recommendationId();
    }

    @Test
    void m3d4b2b2aIndependentSignerSeesStaticCandidateButNoMigrationGrant()
            throws Exception {
        var fixture = m3d4b2bFixture();
        String url = m3d4b2b2aPolicyUrl(fixture);
        var response = m3d4bReviewerGet(url, fixture.signerToken());
        assertEquals(200, response.statusCode(), response.body());
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(response.body());
        assertEquals("ADVISORY_STATIC_FIELD_REVIEW_ONLY_NO_GRANT",
            json.get("state").asText());
        assertEquals(fixture.recommendationId(),
            json.get("recommendationId").asText());
        assertEquals(fixture.recommendationSha256(),
            json.get("recommendationEvidenceSha256").asText());
        assertNotEquals(json.get("originatingReviewerId").asText(),
            json.get("independentSignerId").asText());
        assertEquals(1, json.get("fields").size());
        assertEquals("/data/name", json.get("fields").get(0)
            .get("targetPath").asText());
        assertEquals("STATIC_SAME_PATH_TYPE_CANDIDATE_ONLY",
            json.get("fields").get(0).get("state").asText());
        assertFalse(json.get("canApproveMapping").asBoolean());
        assertFalse(json.get("canReadHistoricalAnswers").asBoolean());
        assertFalse(json.get("canIssueGrant").asBoolean());
        assertFalse(json.get("canTransfer").asBoolean());
        assertFalse(json.get("canSubmit").asBoolean());
        assertFalse(response.body().contains("NONDISCLOSABLE TEST ANSWER"));
        assertFalse(response.body().contains("<data"));

        var reviewerSelf = m3d4bReviewerGet(url, fixture.reviewerToken());
        assertTrue(reviewerSelf.statusCode() == 404 ||
            reviewerSelf.statusCode() == 409);
        var mobile = m3d4bReviewerGet(url, authorization);
        assertTrue(mobile.statusCode() == 401 || mobile.statusCode() == 403);
        var unauthenticated = m3d4bReviewerGet(url, null);
        assertTrue(unauthenticated.statusCode() == 401 ||
            unauthenticated.statusCode() == 403);

        // This GET-only endpoint MUST NOT accept a request to issue grants.
        var issue = m3d4b2Post(url, fixture.signerToken(),
            "{\"canTransfer\":true,\"approvedPaths\":[\"/data/name\"]}");
        assertTrue(issue.statusCode() == 404 || issue.statusCode() == 405,
            "Static field inspector is not a grant issuer");
    }

    @Test
    void m3d4b2b2aTamperedFieldReviewAndIndependentFinalDenialVetoPolicy()
            throws Exception {
        var f = m3d4b2bFixture();
        String url = m3d4b2b2aPolicyUrl(f);
        UUID recommendation = UUID.fromString(f.recommendationId());
        try {
            jdbcTemplate.update("""
                UPDATE typed_mapping_review_fields SET disposition = 'REENTER'
                WHERE recommendation_id = ? AND target_path = '/data/name'
                """, recommendation);
            var tampered = m3d4bReviewerGet(url, f.signerToken());
            assertEquals(409, tampered.statusCode(),
                "A changed field recommendation must not pass evidence rehashing");
        } finally {
            jdbcTemplate.update("""
                UPDATE typed_mapping_review_fields
                SET disposition = 'ESCALATE_FOR_RULE_REVIEW'
                WHERE recommendation_id = ? AND target_path = '/data/name'
                """, recommendation);
        }
        assertEquals(200, m3d4bReviewerGet(
            url, f.signerToken()).statusCode());

        var denial = new com.fasterxml.jackson.databind.ObjectMapper()
            .createObjectNode();
        denial.put("clientRequestId", "policy-veto-" + UUID.randomUUID());
        denial.put("reasonCode", "SCHEMA_UNSAFE");
        denial.put("recommendationEvidenceSha256", f.recommendationSha256());
        var rejected = m3d4b2Post(m3d4b2bUrl(f),
            f.signerToken(), denial.toString());
        assertEquals(201, rejected.statusCode(), rejected.body());
        assertEquals(409, m3d4bReviewerGet(
            url, f.signerToken()).statusCode(),
            "Previously recorded independent denial must veto policy inspection");
    }

}
