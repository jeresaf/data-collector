package org.odk.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.domain.Submission;
import org.odk.platform.repository.SubmissionRepository;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceTest {
    @Mock SubmissionRepository repository;
    @Mock SubmissionProcessingService processing;
    @Mock JavaRosaFormLoader loader;
    @Mock FormVersionService versions;
    @InjectMocks SubmissionService service;

    @Test
    void statusUpdateSetsReviewFieldsAndPersists() {
        UUID id = UUID.randomUUID();
        Submission submission = Submission.builder().id(id).status("SUBMITTED").build();
        when(repository.findById(id)).thenReturn(Optional.of(submission));
        when(repository.save(submission)).thenReturn(submission);
        var updated = service.updateStatus(id, "APPROVED", "ok").orElseThrow();
        assertEquals("APPROVED", updated.getStatus());
        assertEquals("ok", updated.getReviewNotes());
        assertNotNull(updated.getReviewedAt());
        verify(repository).save(submission);
    }

    @Test
    void deleteIsSoftDeleteAndMissingIdFails() {
        UUID id = UUID.randomUUID();
        Submission submission = Submission.builder().id(id).status("SUBMITTED").build();
        when(repository.findById(id)).thenReturn(Optional.of(submission));
        service.deleteSubmission(id);
        assertEquals("DELETED", submission.getStatus());
        verify(repository).save(submission);
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.deleteSubmission(missing));
    }
}
