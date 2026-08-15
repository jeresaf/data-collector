package org.odk.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportRunServiceTest {
    @Test
    void missingDefinitionFailsBeforeCreatingRunRecord() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReportDefinitionService definitions = mock(ReportDefinitionService.class);
        UUID projectId = UUID.randomUUID(), reportId = UUID.randomUUID();
        when(definitions.getDefinition(projectId, reportId))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Report definition not found"));
        ReportRunService service = new ReportRunService(jdbc, definitions, new ObjectMapper());
        assertThrows(ResponseStatusException.class, () -> service.run(projectId, reportId, null));
        verifyNoInteractions(jdbc);
    }
}
