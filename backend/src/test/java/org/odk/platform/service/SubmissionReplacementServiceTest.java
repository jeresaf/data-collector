package org.odk.platform.service;

import org.junit.jupiter.api.Test;
import org.odk.platform.domain.Submission;
import org.odk.platform.repository.SubmissionRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubmissionReplacementServiceTest {
    @Test
    void persistsHistoryBeforeSavingReplacement() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SubmissionRepository repository = mock(SubmissionRepository.class);
        SubmissionReplacementService service = new SubmissionReplacementService(jdbc, repository);
        Submission original = Submission.builder().id(UUID.randomUUID()).instanceId("uuid:old").xmlContent("<old/>").build();
        Submission replacement = Submission.builder().id(UUID.randomUUID()).instanceId("uuid:new").build();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(original.getId()))).thenReturn(2);
        when(repository.saveAndFlush(replacement)).thenReturn(replacement);
        assertSame(replacement, service.createReplacement(original, replacement, "uuid:old"));
        verify(jdbc).update(anyString(), any(), eq(original.getId()), eq(2), eq("<old/>"), eq("uuid:old"), eq("uuid:old"), eq("SUBMITTED"), any(), any());
        verify(repository).saveAndFlush(replacement);
    }

    @Test
    void rejectsUnpersistedOriginal() {
        SubmissionReplacementService service = new SubmissionReplacementService(mock(JdbcTemplate.class), mock(SubmissionRepository.class));
        assertThrows(IllegalArgumentException.class,
                () -> service.createReplacement(new Submission(), new Submission(), "x"));
    }
}
