package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.SubmissionAttachment;
import org.odk.platform.domain.SubmissionAnswer;
import org.odk.platform.repository.SubmissionAttachmentRepository;
import org.odk.platform.repository.SubmissionAnswerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubmissionAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionAttachmentService.class);

    private final SubmissionAttachmentRepository attachmentRepository;
    private final SubmissionAnswerRepository answerRepository;
    private final SubmissionAttachmentJournal journal;

    @Value("${odk.storage.submissions-root:uploads/submissions}")
    private String submissionsRoot;

    /**
     * Stores one respondent-uploaded attachment on local disk and persists its
     * metadata. The returned storage path is absolute and normalized.
     */
    @Transactional(rollbackFor = IOException.class)
    public SubmissionAttachment store(
            Submission submission,
            MultipartFile file,
            String submissionXml) throws IOException {

        if (submission == null || submission.getId() == null) {
            throw new IllegalArgumentException(
                    "Submission must be persisted before storing attachments");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Attachment must not be empty");
        }

        String fileName = safeFileName(file.getOriginalFilename());

        boolean managed = journal.enabled();
        Path target;
        if (managed) {
            target = journal.begin(submission.getId()).target();
        } else {
            Path root = Paths.get(submissionsRoot).toAbsolutePath().normalize();
            Path directory = root.resolve(submission.getId().toString());
            Files.createDirectories(directory);
            if (!directory.toRealPath().equals(directory)) {
                throw new IOException("Unsafe attachment directory");
            }
            target = directory.resolve(UUID.randomUUID() + "_" + fileName);
            // CREATE_NEW establishes ownership before any cleanup is registered.
            Files.createFile(target);
            registerLegacyRollback(target);
        }

        try {
            try (InputStream input = file.getInputStream();
                 var output = Files.newOutputStream(target, StandardOpenOption.WRITE,
                         LinkOption.NOFOLLOW_LINKS)) {
                input.transferTo(output);
            }
            if (Files.size(target) != file.getSize()) throw new IOException("Incomplete attachment");
            if (managed) journal.forcePayload(target);
            SubmissionAnswer linkedAnswer =
                    resolveLinkedAnswer(submission.getId(), fileName);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("originalFileName", fileName);
            metadata.put("referencedInAnswers", linkedAnswer != null);
            metadata.put(
                    "referencedInXml",
                    submissionXml != null && submissionXml.contains(fileName));

            if (linkedAnswer != null) {
                metadata.put("answerId", linkedAnswer.getId().toString());
                metadata.put("answerPath", linkedAnswer.getPath());
                metadata.put("answerName", linkedAnswer.getFieldName());
                if (linkedAnswer.getRepeatPath() != null) {
                    metadata.put("repeatPath", linkedAnswer.getRepeatPath());
                }
                if (linkedAnswer.getRepeatIndex() != null) {
                    metadata.put("repeatIndex", linkedAnswer.getRepeatIndex());
                }
            }

            SubmissionAttachment attachment = SubmissionAttachment.builder()
                    .submission(submission)
                    .answer(linkedAnswer)
                    .fileName(fileName)
                    .contentType(file.getContentType())
                    .fileSize(file.getSize())
                    .storagePath(target.toString())
                    .storageDriver("local")
                    .contentHash(sha256(target))
                    .metadata(metadata)
                    .build();

            return attachmentRepository.saveAndFlush(attachment);
        } catch (IOException | RuntimeException ex) {
            // The journal/transaction callback owns managed cleanup; on an
            // ambiguous outcome retain evidence rather than remove a committed file.
            if (!managed) {
                try { Files.deleteIfExists(target); }
                catch (IOException ignored) { log.warn("Attachment cleanup failed"); }
            }
            throw ex;
        }
    }

    private void registerLegacyRollback(Path target) {
        if (TransactionSynchronizationManager.isActualTransactionActive() &&
                TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        try { Files.deleteIfExists(target); }
                        catch (IOException ignored) { log.warn("Attachment rollback cleanup failed"); }
                    }
                }
            });
        }
    }


    /**
     * Resolve a concrete normalized answer for the uploaded filename.
     *
     * A single exact match is safe. If there are multiple candidates, prefer a
     * single media/binary answer. If ambiguity remains, leave the relationship
     * null rather than creating a wrong foreign-key link.
     */
    private SubmissionAnswer resolveLinkedAnswer(UUID submissionId, String fileName) {
        List<SubmissionAnswer> candidates =
                answerRepository.findBySubmissionIdAndValueText(submissionId, fileName);

        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        List<SubmissionAnswer> mediaCandidates = candidates.stream()
                .filter(this::isMediaAnswer)
                .toList();

        return mediaCandidates.size() == 1 ? mediaCandidates.get(0) : null;
    }

    private boolean isMediaAnswer(SubmissionAnswer answer) {
        if (answer == null || answer.getFieldType() == null) {
            return false;
        }
        String type = answer.getFieldType().trim().toLowerCase(Locale.ROOT);
        return type.equals("binary")
                || type.equals("image")
                || type.equals("audio")
                || type.equals("video")
                || type.equals("file");
    }

    private String safeFileName(String originalFileName) {
        String candidate = StringUtils.cleanPath(
                originalFileName == null ? "" : originalFileName.trim());

        if (!StringUtils.hasText(candidate)
                || candidate.contains("..")
                || candidate.contains("/")
                || candidate.contains("\\")) {
            throw new IllegalArgumentException("Invalid attachment filename");
        }

        return candidate;
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }

            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
