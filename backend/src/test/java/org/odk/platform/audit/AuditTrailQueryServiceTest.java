package org.odk.platform.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuditTrailQueryServiceTest {

    @Autowired AuditTrailQueryService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void search_filtersEnvelopeAndReturnsStructuredDetails() {
        UUID auditId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO audit_logs
                    (id, action, resource_type, resource_id, resource_name, ip_address, user_agent,
                     status, metadata, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, auditId, "FORM_PUBLISHED", "FORM", "form-1", "Registration",
                "203.0.113.10", "ODK Admin Test", "SUCCESS", "{\"source\":\"test\"}",
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO audit_log_details
                    (id, audit_log_id, detail_key, old_value, new_value, value_type, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), auditId, "status", "VALIDATED", "PUBLISHED", "string", "{}");

        var page = service.search(new AuditTrailQueryService.AuditFilter(
                "FORM_PUBLISHED", "FORM", "form-1", "SUCCESS", null, "203.0.113.10",
                now.minusSeconds(30), now.plusSeconds(30), 0, 20));

        assertEquals(1, page.totalElements());
        assertEquals(1, page.items().size());
        var entry = page.items().get(0);
        assertEquals("FORM_PUBLISHED", entry.action());
        assertEquals("Registration", entry.resourceName());
        assertEquals("203.0.113.10", entry.ipAddress());
        assertEquals("ODK Admin Test", entry.userAgent());
        assertEquals("SYSTEM", entry.actorType());
        assertEquals(1, entry.details().size());
        assertEquals("status", entry.details().get(0).key());
        assertEquals("PUBLISHED", entry.details().get(0).newValue());
    }

    @Test
    void search_capsPageSizeAndSupportsActorFilter() {
        UUID userId = UUID.randomUUID();
        String username = "audit-admin-" + userId;
        jdbc.update("""
                INSERT INTO users
                    (id, username, email, password_hash, first_name, last_name, is_active, is_verified,
                     failed_login_attempts, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, userId, username, username + "@example.com", "hash", "Audit", "Admin", true, true, 0);
        jdbc.update("""
                INSERT INTO audit_logs (id, action, resource_type, resource_name, status, user_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), "PROJECT_UPDATED", "PROJECT", "Example", "SUCCESS", userId);

        var page = service.search(new AuditTrailQueryService.AuditFilter(
                null, null, null, null, username.substring(0, 16), null, null, null, 0, 500));

        assertEquals(200, page.size());
        assertTrue(page.items().stream().anyMatch(item -> username.equals(item.actorName())));
    }
}
