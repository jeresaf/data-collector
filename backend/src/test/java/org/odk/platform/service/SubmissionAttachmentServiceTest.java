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
                        attachmentRepository, answerRepository);
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
                        attachmentRepository, answerRepository);
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
    void rejectsPathTraversalFilename() {
        SubmissionAttachmentService service =
                new SubmissionAttachmentService(
                        mock(SubmissionAttachmentRepository.class),
                        mock(SubmissionAnswerRepository.class));
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
