package org.odk.platform.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuditTrailControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void adminCanFilterAndViewAuditTrail() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_logs
                    (id, action, resource_type, resource_id, resource_name, status, ip_address, user_agent)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, "REPORT_EXPORTED", "REPORT", "report-1", "Monthly totals",
                "SUCCESS", "198.51.100.20", "Mozilla/Test");

        mockMvc.perform(get("/api/audit-logs")
                        .param("action", "REPORT_EXPORTED")
                        .param("resourceType", "REPORT")
                        .param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.items[0].action").value("REPORT_EXPORTED"))
                .andExpect(jsonPath("$.items[0].resourceName").value("Monthly totals"))
                .andExpect(jsonPath("$.items[0].ipAddress").value("198.51.100.20"))
                .andExpect(jsonPath("$.items[0].userAgent").value("Mozilla/Test"));
    }

    @Test
    @WithMockUser(username = "viewer", roles = {"VIEWER"})
    void nonAdminCannotBrowseAuditTrail() throws Exception {
        mockMvc.perform(get("/api/audit-logs"))
                .andExpect(status().isForbidden());
    }
}
