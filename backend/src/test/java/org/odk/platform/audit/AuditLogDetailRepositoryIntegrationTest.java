package org.odk.platform.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuditLogDetailRepositoryIntegrationTest {

    @Autowired AuditLogDetailRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void detailPersistsAndLoadsByAuditLog() {
        UUID auditId = UUID.randomUUID();
        jdbc.update("INSERT INTO audit_logs (id, action, resource_type, resource_name) VALUES (?, ?, ?, ?)",
                auditId, "REFERENCE_DATA_CHANGED", "REFERENCE_LIST", "countries");

        AuditLogDetail detail = repository.saveAndFlush(AuditLogDetail.builder()
                .auditLogId(auditId)
                .detailKey("label")
                .oldValue("Old label")
                .newValue("New label")
                .valueType("string")
                .metadata("{\"source\":\"admin\"}")
                .build());

        assertNotNull(detail.getId());
        var loaded = repository.findByAuditLogIdOrderByCreatedAtAsc(auditId);
        assertEquals(1, loaded.size());
        assertEquals("label", loaded.get(0).getDetailKey());
        assertEquals("New label", loaded.get(0).getNewValue());
    }
}
