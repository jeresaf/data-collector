package org.odk.platform.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ReportExportServiceTest {

    @Test
    void csv_quotesCommasAndXlsxCreatesWorkbook() {
        ReportRunService runner = mock(ReportRunService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReportExportService service = new ReportExportService(runner, jdbc, mock(org.odk.platform.audit.AuditService.class));
        var columns = List.of(
                new ReportRunService.ReportColumn("category", "Category", "string", "ANSWER", "NONE", true),
                new ReportRunService.ReportColumn("amount::SUM", "SUM Amount", "decimal", "ANSWER", "SUM", false)
        );
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("category", "A, Central");
        row.put("amount::SUM", 30);
        var result = new ReportRunService.ReportResult(UUID.randomUUID(), UUID.randomUUID(), "Generic report",
                columns, List.of(row), 1, 2, Instant.now());

        String csv = new String(service.csv(result), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"A, Central\",30"));

        byte[] xlsx = service.xlsx(result);
        assertTrue(xlsx.length > 100);
        assertEquals('P', xlsx[0]);
        assertEquals('K', xlsx[1]);
    }
}
