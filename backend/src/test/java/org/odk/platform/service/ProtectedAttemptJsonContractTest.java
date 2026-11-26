package org.odk.platform.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProtectedAttemptJsonContractTest {
    @Test
    void productionNonNullConfigurationStillEmitsExplicitProtocolNulls() throws Exception {
        var mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        UUID work = UUID.randomUUID();
        var attempt = new RecordWorkService.AttemptView(work, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "uuid:" + UUID.randomUUID(), null, null);
        var reserve = mapper.readTree(mapper.writeValueAsBytes(attempt));
        assertEquals(11, reserve.size());
        assertTrue(reserve.has("wireSha256") && reserve.get("wireSha256").isNull());
        assertTrue(reserve.has("acceptedSubmissionId") && reserve.get("acceptedSubmissionId").isNull());
        var status = mapper.readTree(mapper.writeValueAsBytes(new RecordWorkService.AttemptStatus(
                RecordWorkService.AttemptState.RESERVED_UNBOUND, attempt, null)));
        assertEquals(3, status.size());
        assertTrue(status.has("completion") && status.get("completion").isNull());
        assertEquals(reserve, status.get("attempt"));
        var bound = new RecordWorkService.AttemptView(work, attempt.attemptId(), attempt.clientRequestId(),
                attempt.projectId(), attempt.actorId(), attempt.sourceId(), attempt.targetVersionId(),
                attempt.formId(), attempt.instanceId(), "0".repeat(64), null);
        var boundStatus = mapper.readTree(mapper.writeValueAsBytes(new RecordWorkService.AttemptStatus(
                RecordWorkService.AttemptState.BOUND_EXACT_CONTENT_ADMISSIBLE, bound, null)));
        assertEquals(3, boundStatus.size());
        assertEquals(11, boundStatus.get("attempt").size());
        assertEquals("0".repeat(64), boundStatus.get("attempt").get("wireSha256").asText());
        assertTrue(boundStatus.get("attempt").get("acceptedSubmissionId").isNull());
        assertTrue(boundStatus.get("completion").isNull());
    }
}
