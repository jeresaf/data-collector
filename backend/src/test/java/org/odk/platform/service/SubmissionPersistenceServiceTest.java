package org.odk.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javarosa.core.model.FormDef;
import org.junit.jupiter.api.Test;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.SubmissionAnswer;
import org.odk.platform.domain.SubmissionEvent;
import org.odk.platform.repository.SubmissionAnswerRepository;
import org.odk.platform.repository.SubmissionEventRepository;
import org.odk.platform.repository.SubmissionRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SubmissionPersistenceServiceTest {

    @Test
    void persistsJsonTypedAnswersSourceAndLifecycleEvent() {
        SubmissionRepository submissions = mock(SubmissionRepository.class);
        SubmissionAnswerRepository answers = mock(SubmissionAnswerRepository.class);
        SubmissionEventRepository events = mock(SubmissionEventRepository.class);
        FormMetadataExtractor metadata = mock(FormMetadataExtractor.class);
        ObjectMapper mapper = new ObjectMapper();
        SubmissionPersistenceService service = new SubmissionPersistenceService(
                submissions, answers, events, metadata);

        UUID id = UUID.randomUUID();
        Submission submission = Submission.builder()
                .id(id)
                .instanceId("uuid:test")
                .metadata(Map.of("userAgent", "ODK Collect"))
                .build();
        when(submissions.saveAndFlush(any(Submission.class))).thenAnswer(inv -> inv.getArgument(0));

        FormMetadataExtractor.FieldInfo name = FormMetadataExtractor.FieldInfo.builder()
                .xpath("/data/name").name("name").type("string").build();
        FormMetadataExtractor.FieldInfo age = FormMetadataExtractor.FieldInfo.builder()
                .xpath("/data/members/age").name("age").type("int").build();
        when(metadata.extractMetadata(any(FormDef.class))).thenReturn(
                FormMetadataExtractor.FormMetadata.builder().fields(List.of(name, age)).build());

        ObjectNode json = mapper.createObjectNode();
        json.put("name", "Aiko");
        json.set("members", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("age", "31")));
        SubmissionProcessingService.ProcessedSubmission processed =
                new SubmissionProcessingService.ProcessedSubmission(
                        json,
                        Map.of("name", "Aiko", "members[0]/age", "31"),
                        Map.of("formVersion", "1"),
                        "uuid:test");

        Submission saved = service.persistProcessedData(
                submission, processed, mock(FormDef.class), "ODK Collect",
                "RECEIVED", "APP_USER", UUID.randomUUID(), Map.of("projectId", "p"));

        assertEquals(json.toString(), saved.getJsonContent());
        assertEquals("ODK Collect", saved.getMetadata().get("sourceClient"));

        @SuppressWarnings("unchecked")
        var answerCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(answers).saveAllAndFlush(answerCaptor.capture());
        List<SubmissionAnswer> rows = (List<SubmissionAnswer>) answerCaptor.getValue();
        assertEquals(2, rows.size());
        SubmissionAnswer ageRow = rows.stream()
                .filter(row -> "members[0]/age".equals(row.getPath()))
                .findFirst().orElseThrow();
        assertEquals("int", ageRow.getFieldType());
        assertEquals("31", ageRow.getValueNumber().toPlainString());
        assertEquals("members", ageRow.getRepeatPath());
        assertEquals(0, ageRow.getRepeatIndex());

        var eventCaptor = org.mockito.ArgumentCaptor.forClass(SubmissionEvent.class);
        verify(events).save(eventCaptor.capture());
        assertEquals("RECEIVED", eventCaptor.getValue().getEventType());
        assertTrue(eventCaptor.getValue().getDetails().containsKey("answerCount"));
    }
}
