package org.odk.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.SubmissionAnswer;
import org.odk.platform.domain.SubmissionAttachment;
import org.odk.platform.repository.SubmissionAnswerRepository;
import org.odk.platform.repository.SubmissionAttachmentRepository;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.nio.file.Files;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SubmissionAttachmentServiceTest {
    @TempDir Path tempDir;

    @Test
    void storesAttachmentAndLinksConcreteNormalizedAnswer() throws Exception {
        SubmissionAttachmentRepository attachmentRepository =
                mock(SubmissionAttachmentRepository.class);
        SubmissionAnswerRepository answerRepository =
                mock(SubmissionAnswerRepository.class);

        when(attachmentRepository.saveAndFlush(any(SubmissionAttachment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UUID submissionId = UUID.randomUUID();
        UUID answerId = UUID.randomUUID();
        Submission submission = Submission.builder().id(submissionId).build();
        SubmissionAnswer answer = SubmissionAnswer.builder()
                .id(answerId)
                .submission(submission)
                .path("members[1]/photo")
                .fieldName("photo")
                .fieldType("binary")
                .valueText("photo.jpg")
                .repeatPath("members")
                .repeatIndex(1)
                .build();

        when(answerRepository.findBySubmissionIdAndValueText(
                submissionId, "photo.jpg"))
                .thenReturn(List.of(answer));

        SubmissionAttachmentService service =
                new SubmissionAttachmentService(
                        attachmentRepository, answerRepository, mock(SubmissionAttachmentJournal.class));
        ReflectionTestUtils.setField(
                service, "submissionsRoot", tempDir.toString());

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        SubmissionAttachment saved =
                service.store(submission, file, "<photo>photo.jpg</photo>");

        assertEquals("photo.jpg", saved.getFileName());
        assertSame(answer, saved.getAnswer());
        assertEquals(Boolean.TRUE, saved.getMetadata().get("referencedInAnswers"));
        assertEquals(answerId.toString(), saved.getMetadata().get("answerId"));
        assertEquals("members[1]/photo", saved.getMetadata().get("answerPath"));
        assertEquals("photo", saved.getMetadata().get("answerName"));
        assertEquals("members", saved.getMetadata().get("repeatPath"));
        assertEquals(1, saved.getMetadata().get("repeatIndex"));
        assertTrue(Path.of(saved.getStoragePath())
                .startsWith(tempDir.toAbsolutePath()));
        assertNotNull(saved.getContentHash());
    }

    @Test
    void storesUnreferencedExtraFileWithoutInventingAnswerLink() throws Exception {
        SubmissionAttachmentRepository attachmentRepository =
                mock(SubmissionAttachmentRepository.class);
        SubmissionAnswerRepository answerRepository =
                mock(SubmissionAnswerRepository.class);

        when(attachmentRepository.saveAndFlush(any(SubmissionAttachment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UUID submissionId = UUID.randomUUID();
        Submission submission = Submission.builder().id(submissionId).build();

        when(answerRepository.findBySubmissionIdAndValueText(
                submissionId, "extra.jpg"))
                .thenReturn(List.of());

        SubmissionAttachmentService service =
                new SubmissionAttachmentService(
                        attachmentRepository, answerRepository, mock(SubmissionAttachmentJournal.class));
        ReflectionTestUtils.setField(
                service, "submissionsRoot", tempDir.toString());

        MockMultipartFile file = new MockMultipartFile(
                "file", "extra.jpg", "image/jpeg", new byte[]{1});

        SubmissionAttachment saved =
                service.store(submission, file, "<name>Example</name>");

        assertNull(saved.getAnswer());
        assertEquals(Boolean.FALSE, saved.getMetadata().get("referencedInAnswers"));
        assertFalse(saved.getMetadata().containsKey("answerId"));
    }

    @Test
    void failedIntakeTransactionRemovesAlreadyWrittenAttachment() throws Exception {
        Path file = fileAfterSimulatedTransactionCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK);
        assertFalse(Files.exists(file),
                "A rolled-back submission must not leave its image on disk");
    }

    @Test
    void successfulIntakeTransactionKeepsWrittenAttachment() throws Exception {
        Path file = fileAfterSimulatedTransactionCompletion(
                TransactionSynchronization.STATUS_COMMITTED);
        assertTrue(Files.exists(file),
                "A committed submission must retain its attachment");
    }

    private Path fileAfterSimulatedTransactionCompletion(int completionStatus)
            throws Exception {
        SubmissionAttachmentRepository attachmentRepository =
                mock(SubmissionAttachmentRepository.class);
        SubmissionAnswerRepository answerRepository =
                mock(SubmissionAnswerRepository.class);
        when(attachmentRepository.saveAndFlush(any(SubmissionAttachment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UUID submissionId = UUID.randomUUID();
        when(answerRepository.findBySubmissionIdAndValueText(
                submissionId, "visit-photo.jpg")).thenReturn(List.of());
        var service = new SubmissionAttachmentService(
                attachmentRepository, answerRepository, mock(SubmissionAttachmentJournal.class));
        ReflectionTestUtils.setField(service, "submissionsRoot", tempDir.toString());
        var submission = Submission.builder().id(submissionId).build();
        var photo = new MockMultipartFile(
                "photo", "visit-photo.jpg", "image/jpeg", new byte[]{1, 2, 3, 4});

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            var result = service.store(
                    submission, photo, "<photo>visit-photo.jpg</photo>");
            Path stored = Path.of(result.getStoragePath());
            assertTrue(Files.exists(stored), "The file must exist before completion");

            var synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size(),
                    "Attachment storage must register a rollback callback");
            for (TransactionSynchronization synchronization : synchronizations) {
                synchronization.afterCompletion(completionStatus);
            }
            return stored;
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void partialInputFailureDoesNotLeaveLegacyPayload() throws Exception {
        var service = new SubmissionAttachmentService(mock(SubmissionAttachmentRepository.class),
                mock(SubmissionAnswerRepository.class), mock(SubmissionAttachmentJournal.class));
        ReflectionTestUtils.setField(service, "submissionsRoot", tempDir.toString());
        var file = mock(org.springframework.web.multipart.MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("photo.jpg");
        when(file.getSize()).thenReturn(5L);
        when(file.getInputStream()).thenReturn(new java.io.InputStream() {
            int count;
            @Override public int read() throws java.io.IOException {
                if (++count < 3) return 1;
                throw new java.io.IOException("synthetic partial read");
            }
        });
        assertThrows(java.io.IOException.class, () ->
                service.store(Submission.builder().id(UUID.randomUUID()).build(), file, ""));
        try (var files = Files.walk(tempDir)) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void rejectsPathTraversalFilename() {
        SubmissionAttachmentService service =
                new SubmissionAttachmentService(
                        mock(SubmissionAttachmentRepository.class),
                        mock(SubmissionAnswerRepository.class), mock(SubmissionAttachmentJournal.class));
        ReflectionTestUtils.setField(
                service, "submissionsRoot", tempDir.toString());

        Submission submission =
                Submission.builder().id(UUID.randomUUID()).build();
        MockMultipartFile file = new MockMultipartFile(
                "file", "../photo.jpg", "image/jpeg", new byte[]{1});

        assertThrows(
                IllegalArgumentException.class,
                () -> service.store(submission, file, ""));
    }
}
