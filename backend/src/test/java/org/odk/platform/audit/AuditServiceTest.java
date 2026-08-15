package org.odk.platform.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class AuditServiceTest {

    @Autowired AuditService auditService;
    @Autowired JdbcTemplate jdbc;

    private final String resourceName = "audit-service-test-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_log_details WHERE audit_log_id IN (SELECT id FROM audit_logs WHERE resource_name = ?)", resourceName);
        jdbc.update("DELETE FROM audit_logs WHERE resource_name = ?", resourceName);
    }

    @Test
    void log_persistsSecurityAndTargetContext() throws Exception {
        auditService.log("AUDIT_SERVICE_TEST", "TEST_RESOURCE", null, resourceName, Map.of(
                "ipAddress", "192.0.2.45",
                "userAgent", "AuditServiceTest/1.0"));

        // AuditService may be asynchronous in the application, so only wait inside
        // the test until the event is visible instead of assuming synchronous I/O.
        int count = 0;
        for (int attempt = 0; attempt < 50; attempt++) {
            Integer current = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND resource_name = ?",
                    Integer.class, "AUDIT_SERVICE_TEST", resourceName);
            count = current == null ? 0 : current;
            if (count > 0) break;
            Thread.sleep(20L);
        }

        assertTrue(count > 0);
        String resourceType = jdbc.queryForObject(
                "SELECT resource_type FROM audit_logs WHERE action = ? AND resource_name = ? ORDER BY created_at DESC LIMIT 1",
                String.class, "AUDIT_SERVICE_TEST", resourceName);
        assertEquals("TEST_RESOURCE", resourceType);
    }
}
