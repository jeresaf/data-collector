package org.odk.platform.service;

import org.odk.platform.domain.FormAttachment;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.User;
import org.odk.platform.repository.FormAttachmentRepository;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.security.CurrentUserService;
import org.odk.platform.service.storage.StorageService;
import org.odk.platform.service.storage.StorageServiceFactory;
import org.odk.platform.web.dto.form.FormAttachmentDto;
import org.odk.platform.web.dto.form.FormAttachmentUploadRequest;
import org.odk.platform.web.exception.ResourceNotFoundException;
import org.odk.platform.web.exception.StorageException;
import org.odk.platform.web.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class FormAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(FormAttachmentService.class);

    private final FormAttachmentRepository attachmentRepository;
    private final FormVersionRepository formVersionRepository;
    private final StorageServiceFactory storageServiceFactory;
    private final CurrentUserService currentUserService;

    @Value("${app.storage.max-file-size:104857600}")
    private long maxFileSize;

    @Value("${app.storage.allowed-extensions:xml,xlsx,xls,csv,json,geojson,png,jpg,jpeg,gif,wav,mp3,mp4,3gp,amr}")
    private List<String> allowedExtensions;

    public FormAttachmentService(FormAttachmentRepository attachmentRepository,
                                 FormVersionRepository formVersionRepository,
                                 StorageServiceFactory storageServiceFactory,
                                 CurrentUserService currentUserService) {
        this.attachmentRepository = attachmentRepository;
        this.formVersionRepository = formVersionRepository;
        this.storageServiceFactory = storageServiceFactory;
        this.currentUserService = currentUserService;
    }

    @Transactional(readOnly = true)
    public Page<FormAttachmentDto> getAttachmentsByFormVersion(UUID formVersionId, Pageable pageable) {
        FormVersion formVersion = formVersionRepository.findById(formVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("Form version not found: " + formVersionId));
        return attachmentRepository.findByFormVersionId(formVersionId, pageable)
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public List<FormAttachmentDto> getAllAttachmentsByFormVersion(UUID formVersionId) {
        FormVersion formVersion = formVersionRepository.findById(formVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("Form version not found: " + formVersionId));
        return attachmentRepository.findByFormVersionId(formVersionId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<FormAttachmentDto> getAttachmentById(UUID attachmentId) {
        return attachmentRepository.findById(attachmentId)
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public Optional<FormAttachmentDto> getAttachmentByFormVersionAndFileName(UUID formVersionId, String fileName) {
        return attachmentRepository.findByFormVersionIdAndFileName(formVersionId, fileName)
                .map(this::toDto);
    }

    public FormAttachmentDto uploadAttachment(UUID formVersionId, FormAttachmentUploadRequest request, MultipartFile file) {
        FormVersion formVersion = formVersionRepository.findById(formVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("Form version not found: " + formVersionId));

        validateFile(file, request.getFileName());

        String contentHash = calculateContentHash(file);
        
        // Check for duplicate content hash
        List<FormAttachment> existingAttachments = attachmentRepository.findByContentHash(contentHash);
        if (!existingAttachments.isEmpty()) {
            log.info("Attachment with same content hash already exists: {}", contentHash);
        }

        // Check for duplicate filename in same form version
        if (attachmentRepository.existsByFormVersionIdAndFileName(formVersionId, request.getFileName())) {
            throw new ValidationException("Attachment with filename '" + request.getFileName() + "' already exists for this form version");
        }

        User currentUser = currentUserService.getCurrentWebUser()
                .orElseThrow(() -> new IllegalStateException("No authenticated web user"));

        StorageService storageService = storageServiceFactory.getStorageService(request.getStorageDriver());
        
        String storagePath = generateStoragePath(formVersionId, request.getFileName());
        
        try {
            storageService.store(file.getInputStream(), storagePath, request.getStorageDriver());
        } catch (IOException e) {
            log.error("Failed to store attachment: {}", e.getMessage(), e);
            throw new StorageException("Failed to store attachment: " + e.getMessage(), e);
        }

        FormAttachment attachment = FormAttachment.builder()
                .fileName(request.getFileName())
                .contentType(file.getContentType())
                .fileSize(file.getSize())
                .storagePath(storagePath)
                .storageDriver(request.getStorageDriver())
                .contentHash(contentHash)
                .isEncrypted(request.getIsEncrypted() != null ? request.getIsEncrypted() : false)
                .encryptionKey(request.getEncryptionKey())
                .formVersion(formVersion)
                .createdBy(currentUser)
                .updatedBy(currentUser)
                .build();

        FormAttachment saved = attachmentRepository.save(attachment);
        log.info("Uploaded attachment {} for form version {}", saved.getId(), formVersionId);
        
        return toDto(saved);
    }

    public FormAttachmentDto updateAttachment(UUID attachmentId, FormAttachmentUploadRequest request) {
        FormAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found: " + attachmentId));

        if (request.getFileName() != null && !request.getFileName().equals(attachment.getFileName())) {
            if (attachmentRepository.existsByFormVersionIdAndFileName(attachment.getFormVersion().getId(), request.getFileName())) {
                throw new ValidationException("Attachment with filename '" + request.getFileName() + "' already exists for this form version");
            }
            attachment.setFileName(request.getFileName());
        }

        if (request.getIsEncrypted() != null) {
            attachment.setIsEncrypted(request.getIsEncrypted());
        }
        if (request.getEncryptionKey() != null) {
            attachment.setEncryptionKey(request.getEncryptionKey());
        }
        if (request.getStorageDriver() != null) {
            attachment.setStorageDriver(request.getStorageDriver());
        }

        attachment.setUpdatedBy(currentUserService.getCurrentWebUser()
                .orElseThrow(() -> new IllegalStateException("No authenticated web user")));

        FormAttachment saved = attachmentRepository.save(attachment);
        log.info("Updated attachment {}", attachmentId);
        
        return toDto(saved);
    }

    public void deleteAttachment(UUID attachmentId) {
        FormAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found: " + attachmentId));

        StorageService storageService = storageServiceFactory.getStorageService(attachment.getStorageDriver());
        try {
            storageService.delete(attachment.getStoragePath());
        } catch (Exception e) {
            log.warn("Failed to delete attachment from storage: {}", e.getMessage());
        }

        attachmentRepository.delete(attachment);
        log.info("Deleted attachment {}", attachmentId);
    }

    @Transactional(readOnly = true)
    public byte[] downloadAttachment(UUID attachmentId) {
        FormAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found: " + attachmentId));

        StorageService storageService = storageServiceFactory.getStorageService(attachment.getStorageDriver());
        try {
            return storageService.read(attachment.getStoragePath());
        } catch (IOException e) {
            log.error("Failed to read attachment from storage: {}", e.getMessage(), e);
            throw new StorageException("Failed to read attachment: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public String getAttachmentContentType(UUID attachmentId) {
        FormAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found: " + attachmentId));
        return attachment.getContentType();
    }

    @Transactional(readOnly = true)
    public String getAttachmentFileName(UUID attachmentId) {
        FormAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found: " + attachmentId));
        return attachment.getFileName();
    }

    @Transactional(readOnly = true)
    public long getAttachmentCount(UUID formVersionId) {
        return attachmentRepository.countByFormVersionId(formVersionId);
    }

    @Transactional(readOnly = true)
    public long getTotalAttachmentSize(UUID formVersionId) {
        Long size = attachmentRepository.sumFileSizeByFormVersionId(formVersionId);
        return size != null ? size : 0L;
    }

    private void validateFile(MultipartFile file, String fileName) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("File is empty");
        }

        if (file.getSize() > maxFileSize) {
            throw new ValidationException("File size exceeds maximum allowed size of " + maxFileSize + " bytes");
        }

        String extension = getFileExtension(fileName);
        if (!allowedExtensions.contains(extension.toLowerCase())) {
            throw new ValidationException("File extension '" + extension + "' is not allowed. Allowed: " + allowedExtensions);
        }
    }

    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        return fileName.substring(lastDotIndex + 1);
    }

    private String calculateContentHash(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(file.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            log.error("Failed to calculate content hash: {}", e.getMessage(), e);
            throw new StorageException("Failed to calculate content hash", e);
        }
    }

    private String generateStoragePath(UUID formVersionId, String fileName) {
        String datePath = Instant.now().toString().substring(0, 10).replace("-", "/");
        return Paths.get("attachments", formVersionId.toString(), datePath, fileName).toString();
    }

    private FormAttachmentDto toDto(FormAttachment attachment) {
        return FormAttachmentDto.builder()
                .id(attachment.getId())
                .fileName(attachment.getFileName())
                .contentType(attachment.getContentType())
                .fileSize(attachment.getFileSize())
                .storagePath(attachment.getStoragePath())
                .storageDriver(attachment.getStorageDriver())
                .contentHash(attachment.getContentHash())
                .isEncrypted(attachment.getIsEncrypted())
                .formVersionId(attachment.getFormVersion().getId())
                .formVersionVersion(attachment.getFormVersion().getVersion())
                .createdById(attachment.getCreatedBy() != null ? attachment.getCreatedBy().getId() : null)
                .createdByUsername(attachment.getCreatedBy() != null ? attachment.getCreatedBy().getUsername() : null)
                .createdAt(attachment.getCreatedAt())
                .updatedAt(attachment.getUpdatedAt())
                .build();
    }
}