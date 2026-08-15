package org.odk.platform.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceDataServiceTest {

    @Test
    void parseCsv_supportsQuotedCommasQuotesAndNewlines() {
        String csv = "name,label,notes\n" +
                "a,Alpha,plain\n" +
                "b,\"Beta, Central\",\"line 1\nline 2\"\n" +
                "c,Gamma,\"say \"\"hello\"\"\"\n";

        List<List<String>> rows = ReferenceDataService.parseCsv(csv);

        assertEquals(4, rows.size());
        assertEquals("Beta, Central", rows.get(2).get(1));
        assertEquals("line 1\nline 2", rows.get(2).get(2));
        assertEquals("say \"hello\"", rows.get(3).get(2));
    }

    @Test
    void parseCsv_rejectsUnclosedQuotedField() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> ReferenceDataService.parseCsv("name,label\na,\"broken"));
        assertEquals(400, ex.getStatusCode().value());
    }
}
