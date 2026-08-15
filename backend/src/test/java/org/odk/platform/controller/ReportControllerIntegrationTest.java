package org.odk.platform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.odk.platform.OdkPlatformApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = OdkPlatformApplication.class, properties = "odk.test.report-controller-integration=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "admin", roles = {"ADMIN"})
class ReportControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    UUID userId;
    UUID projectId;
    UUID formId;
    UUID formVersionId;
    UUID appUserId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        formId = UUID.randomUUID();
        formVersionId = UUID.randomUUID();
        appUserId = UUID.randomUUID();

        jdbc.update("INSERT INTO users (id, username, email, password_hash, is_active, is_verified) VALUES (?, 'report-admin', ?, 'x', true, true)",
                userId, "report-admin-" + userId + "@example.org");
        jdbc.update("INSERT INTO projects (id, name, owner_id, created_by_id) VALUES (?, 'Reporting Project', ?, ?)", projectId, userId, userId);
        jdbc.update("""
                INSERT INTO xforms (id, title, xmlns, version, xml_content, project_id, created_by_id)
                VALUES (?, 'Generic Report Form', 'report_form', '1', ?, ?, ?)
                """, formId, xform(), projectId, userId);
        jdbc.update("""
                INSERT INTO form_versions (id, form_id, version, xml_content, xmlns, status, is_latest, is_published)
                VALUES (?, ?, '1', ?, 'report_form', 'PUBLISHED', true, true)
                """, formVersionId, formId, xform());
        jdbc.update("INSERT INTO app_users (id, username, email, password_hash, is_active, is_verified) VALUES (?, ?, ?, 'x', true, true)",
                appUserId, "collector-" + appUserId, "collector-" + appUserId + "@example.org");

        addSubmission("uuid:one", "A", "10", Instant.parse("2026-08-10T10:00:00Z"), "ODK Collect");
        addSubmission("uuid:two", "A", "20", Instant.parse("2026-08-11T10:00:00Z"), "ODK Collect");
        addSubmission("uuid:three", "B", "5", Instant.parse("2026-08-12T10:00:00Z"), "KoboToolbox");
    }

    @Test
    void builderRunGroupingFiltersAndExports_areGeneric() throws Exception {
        mockMvc.perform(get("/api/projects/{projectId}/reports/forms/{formId}/fields", projectId, formId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.fieldPath == 'category')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.fieldPath == '$submission.source')]", hasSize(1)));

        String response = mockMvc.perform(post("/api/projects/{projectId}/reports", projectId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Category totals",
                                "formId", formId,
                                "reportType", "SUMMARY",
                                "fields", List.of(
                                        Map.of("fieldPath", "category", "fieldKind", "ANSWER", "label", "Category", "dataType", "string", "groupBy", true),
                                        Map.of("fieldPath", "$submission.id", "fieldKind", "SYSTEM", "label", "Submissions", "aggregation", "COUNT"),
                                        Map.of("fieldPath", "amount", "fieldKind", "ANSWER", "label", "Amount", "dataType", "decimal", "aggregation", "AVG")
                                ),
                                "filters", List.of()
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID reportId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        String runResponse = mockMvc.perform(post("/api/projects/{projectId}/reports/{reportId}/run", projectId, reportId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(2))
                .andExpect(jsonPath("$.sourceSubmissionCount").value(3))
                .andReturn().getResponse().getContentAsString();
        JsonNode runJson = objectMapper.readTree(runResponse);
        JsonNode categoryA = java.util.stream.StreamSupport.stream(runJson.get("rows").spliterator(), false)
                .filter(row -> "A".equals(row.get("category").asText()))
                .findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(2, categoryA.get("$submission.id::COUNT").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(15.0, categoryA.get("amount::AVG").asDouble(), 0.001);

        mockMvc.perform(post("/api/projects/{projectId}/reports/{reportId}/run", projectId, reportId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "filters", List.of(Map.of("fieldPath", "category", "fieldKind", "ANSWER", "operator", "EQ", "value", "B"))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.sourceSubmissionCount").value(1));

        mockMvc.perform(post("/api/projects/{projectId}/reports/{reportId}/export/csv", projectId, reportId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(content().string(containsString("Category,COUNT Submissions,AVG Amount")))
                .andExpect(content().string(containsString("A,2,15")));

        mockMvc.perform(post("/api/projects/{projectId}/reports/{reportId}/export/xlsx", projectId, reportId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("spreadsheetml.sheet")));

        Integer runCount = jdbc.queryForObject("SELECT COUNT(*) FROM report_runs WHERE report_definition_id = ? AND status = 'COMPLETED'",
                Integer.class, reportId);
        Integer exportCount = jdbc.queryForObject("SELECT COUNT(*) FROM report_exports", Integer.class);
        org.junit.jupiter.api.Assertions.assertTrue(runCount != null && runCount >= 4);
        org.junit.jupiter.api.Assertions.assertEquals(2, exportCount);
    }

    @Test
    @WithMockUser(username = "viewer", roles = {"USER"})
    void viewerCanReadAndRunButCannotCreate() throws Exception {
        String response = mockMvc.perform(post("/api/projects/{projectId}/reports", projectId)
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Viewer report", "formId", formId,
                                "fields", List.of(Map.of("fieldPath", "category", "fieldKind", "ANSWER"))
                        ))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID reportId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        mockMvc.perform(get("/api/projects/{projectId}/reports", projectId)).andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/{projectId}/reports/{reportId}/run", projectId, reportId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/{projectId}/reports", projectId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Blocked", "formId", formId))))
                .andExpect(status().isForbidden());
    }

    @Test
    void dashboardWidgetCanReferenceSavedReport() throws Exception {
        String response = mockMvc.perform(post("/api/projects/{projectId}/reports", projectId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Widget report", "formId", formId,
                                "fields", List.of(Map.of("fieldPath", "$submission.id", "fieldKind", "SYSTEM", "aggregation", "COUNT"))
                        ))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID reportId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

        mockMvc.perform(post("/api/projects/{projectId}/reports/widgets", projectId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reportDefinitionId", reportId, "title", "Submission count", "widgetType", "NUMBER"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.widgetType").value("NUMBER"));
        mockMvc.perform(get("/api/projects/{projectId}/reports/widgets", projectId))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    private void addSubmission(String instanceId, String category, String amount, Instant submittedAt, String source) {
        UUID submissionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions
                    (id, form_id, form_version_id, instance_id, xml_content, json_content, status, submitted_at, submitted_by_id, metadata)
                VALUES (?, ?, ?, ?, '<data/>', '{}', 'SUBMITTED', ?, ?, ?)
                """, submissionId, formId, formVersionId, instanceId, Timestamp.from(submittedAt), appUserId,
                "{\"sourceClient\":\"" + source + "\"}");
        jdbc.update("""
                INSERT INTO submission_answers (id, submission_id, path, field_name, field_type, value_text)
                VALUES (?, ?, 'category', 'category', 'string', ?)
                """, UUID.randomUUID(), submissionId, category);
        jdbc.update("""
                INSERT INTO submission_answers (id, submission_id, path, field_name, field_type, value_number, value_text)
                VALUES (?, ?, 'amount', 'amount', 'decimal', ?, ?)
                """, UUID.randomUUID(), submissionId, new java.math.BigDecimal(amount), amount);
    }

    private static String xform() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns="http://www.w3.org/2002/xforms">
                  <h:head>
                    <h:title>Generic Report Form</h:title>
                    <model>
                      <instance><data id="report_form" version="1"><category/><amount/><meta><instanceID/></meta></data></instance>
                      <bind nodeset="/data/category" type="string"/>
                      <bind nodeset="/data/amount" type="decimal"/>
                    </model>
                  </h:head>
                  <h:body>
                    <input ref="/data/category"><label>Category</label></input>
                    <input ref="/data/amount"><label>Amount</label></input>
                  </h:body>
                </h:html>
                """;
    }
}
