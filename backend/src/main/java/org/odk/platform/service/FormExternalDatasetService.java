package org.odk.platform.service;

import org.odk.platform.domain.FormExternalDataset;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.User;
import org.odk.platform.repository.FormExternalDatasetRepository;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.security.CurrentUserService;
import org.odk.platform.service.storage.StorageService;
import org.odk.platform.service.storage.StorageServiceFactory;
import org.odk.platform.web.dto.form.FormExternalDatasetDto;
import org.odk.platform.web.dto.form.FormExternalDatasetUploadRequest;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class FormExternalDatasetService {

    private static final Logger log = LoggerFactory.getLogger(FormExternalDatasetService.class);

    private final FormExternalDatasetRepository externalDatasetRepository;
    private final FormVersionRepository formVersionRepository;
    private final StorageServiceFactory storageServiceFactory;
    private final CurrentUserService currentUserService;

    @Value("${app.storage.max-file-size:104857600}")
    private long maxFileSize;

    @Value("${app.storage.allowed-extensions:csv,json,geojson,xml,xlsx,xls}")
    private List<String> allowedExtensions;

    public FormExternalDatasetService(FormExternalDatasetRepository externalDatasetRepository,
                                      FormVersionRepository formVersionRepository,
                                      StorageServiceFactory storageServiceFactory,
                                      CurrentUserService currentUserService) {
        this.externalDatasetRepository = externalDatasetRepository;
        this.formVersionRepository = formVersionRepository;
        this.storageServiceFactory = storageServiceFactory;
        this.currentUserService = currentUserService;
    }

    @Transactional(readOnly = true)
    public Page<FormExternalDatasetDto> getExternalDatasetsByFormVersion(UUID formVersionId, Pageable pageable) {
        FormVersion formVersion = formVersionRepository.findById(formVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("Form version not found: " + formVersionId));
        return externalDatasetRepository.findByFormVersionId(formVersionId, pageable)
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public List<FormExternalDatasetDto> getAllExternalDatasetsByFormVersion(UUID formVersionId) {
        FormVersion formVersion = formVersionRepository.findById(formVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("Form version not found: " + formVersionId));
        return externalDatasetRepository.findByFormVersionId(formVersionId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FormExternalDatasetDto> getActiveExternalDatasetsByFormVersion(UUID formVersionId) {
        FormVersion formVersion = formVersionRepository.findById(formVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("Form version not found: " + formVersionId));
        return externalDatasetRepository.findByFormVersionIdAndIsActiveTrue(formVersionId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<FormExternalDatasetDto> getExternalDatasetById(UUID externalDatasetId) {
        return externalDatasetRepository.findById(externalDatasetId)
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public Optional<FormExternalDatasetDto> getExternalDatasetByFormVersionAndDatasetId(UUID formVersionId, String datasetId) {
        return externalDatasetRepository.findByFormVersionIdAndDatasetId(formVersionId, datasetId)
                .map(this::toDto);
    }

    public FormExternalDatasetDto uploadExternalDataset(UUID formVersionId, FormExternalDatasetUploadRequest request, MultipartFile file) {
        FormVersion formVersion = formVersionRepository.findById(formVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("Form version not found: " + formVersionId));

        validateFile(file, request.getDatasetId());

        String contentHash = calculateContentHash(file);
        
        // Check for duplicate content hash
        List<FormExternalDataset> existingDatasets = externalDatasetRepository.findByContentHash(contentHash);
        if (!existingDatasets.isEmpty()) {
            log.info("External dataset with same content hash already exists: {}", contentHash);
        }

        // Check for duplicate dataset_id in same form version
        if (externalDatasetRepository.existsByFormVersionIdAndDatasetId(formVersionId, request.getDatasetId())) {
            throw new ValidationException("External dataset with dataset_id '" + request.getDatasetId() + "' already exists for this form version");
        }

        User currentUser = currentUserService.getCurrentWebUser()
                .orElseThrow(() -> new IllegalStateException("No authenticated web user"));

        StorageService storageService = storageServiceFactory.getStorageService(request.getStorageDriver());
        
        String storagePath = generateStoragePath(formVersionId, request.getDatasetId(), file.getOriginalFilename());
        
        try {
            storageService.store(file.getInputStream(), storagePath, request.getStorageDriver());
        } catch (IOException e) {
            log.error("Failed to store external dataset: {}", e.getMessage(), e);
            throw new StorageException("Failed to store external dataset: " + e.getMessage(), e);
        }

        FormExternalDataset externalDataset = FormExternalDataset.builder()
                .datasetId(request.getDatasetId())
                .title(request.getTitle())
                .description(request.getDescription())
                .contentType(file.getContentType())
                .storagePath(storagePath)
                .storageDriver(request.getStorageDriver())
                .contentHash(contentHash)
                .fileSize(file.getSize())
                .rowCount(request.getRowCount())
                .columnDefinitions(request.getColumnDefinitions() != null ? request.getColumnDefinitions() : Map.of())
                .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                .formVersion(formVersion)
                .createdBy(currentUser)
                .updatedBy(currentUser)
                .build();

        FormExternalDataset saved = externalDatasetRepository.save(externalDataset);
        log.info("Uploaded external dataset {} for form version {}", saved.getId(), formVersionId);
        
        return toDto(saved);
    }

    public FormExternalDatasetDto updateExternalDataset(UUID externalDatasetId, FormExternalDatasetUploadRequest request) {
        FormExternalDataset externalDataset = externalDatasetRepository.findById(externalDatasetId)
                .orElseThrow(() -> new ResourceNotFoundException("External dataset not found: " + externalDatasetId));

        if (request.getTitle() != null) {
            externalDataset.setTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            externalDataset.setDescription(request.getDescription());
        }
        if (request.getIsActive() != null) {
            externalDataset.setIsActive(request.getIsActive());
        }
        if (request.getRowCount() != null) {
            externalDataset.setRowCount(request.getRowCount());
        }
        if (request.getColumnDefinitions() != null) {
            externalDataset.setColumnDefinitions(request.getColumnDefinitions());
        }
        if (request.getStorageDriver() != null) {
            externalDataset.setStorageDriver(request.getStorageDriver());
        }

        externalDataset.setUpdatedBy(currentUserService.getCurrentWebUser()
                .orElseThrow(() -> new IllegalStateException("No authenticated web user")));

        FormExternalDataset saved = externalDatasetRepository.save(externalDataset);
        log.info("Updated external dataset {}", externalDatasetId);
        
        return toDto(saved);
    }

    public void deleteExternalDataset(UUID externalDatasetId) {
        FormExternalDataset externalDataset = externalDatasetRepository.findById(externalDatasetId)
                .orElseThrow(() -> new ResourceNotFoundException("External dataset not found: " + externalDatasetId));

        StorageService storageService = storageServiceFactory.getStorageService(externalDataset.getStorageDriver());
        try {
            storageService.delete(externalDataset.getStoragePath());
        } catch (Exception e) {
            log.warn("Failed to delete external dataset from storage: {}", e.getMessage());
        }

        externalDatasetRepository.delete(externalDataset);
        log.info("Deleted external dataset {}", externalDatasetId);
    }

    /**
     * Load active external datasets for the JavaRosa web runtime.
     *
     * <p>The map deliberately includes both the configured dataset id and the
     * storage/source filename aliases so XForms that use either convention can
     * resolve the same form-version-scoped dataset without exposing storage paths
     * to JavaRosa.</p>
     */
    @Transactional(readOnly = true)
    public Map<String, byte[]> loadActiveDatasetContents(UUID formVersionId) {
        List<FormExternalDataset> datasets =
                externalDatasetRepository.findByFormVersionIdAndIsActiveTrue(formVersionId);
        Map<String, byte[]> contents = new LinkedHashMap<>();

        for (FormExternalDataset dataset : datasets) {
            StorageService storageService = storageServiceFactory.getStorageService(dataset.getStorageDriver());
            try {
                byte[] bytes = storageService.read(dataset.getStoragePath());
                contents.put(dataset.getDatasetId(), bytes);

                if (dataset.getStoragePath() != null && !dataset.getStoragePath().isBlank()) {
                    String normalized = dataset.getStoragePath().replace('\\', '/');
                    String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
                    contents.putIfAbsent(fileName, bytes);
                }
            } catch (IOException e) {
                log.error("Failed to load external dataset {} for form version {}: {}",
                        dataset.getDatasetId(), formVersionId, e.getMessage(), e);
                throw new StorageException("Failed to load external dataset '" + dataset.getDatasetId() + "'", e);
            }
        }

        return Map.copyOf(contents);
    }

    @Transactional(readOnly = true)
    public byte[] downloadExternalDataset(UUID externalDatasetId) {
        FormExternalDataset externalDataset = externalDatasetRepository.findById(externalDatasetId)
                .orElseThrow(() -> new ResourceNotFoundException("External dataset not found: " + externalDatasetId));

        StorageService storageService = storageServiceFactory.getStorageService(externalDataset.getStorageDriver());
        try {
            return storageService.read(externalDataset.getStoragePath());
        } catch (IOException e) {
            log.error("Failed to read external dataset from storage: {}", e.getMessage(), e);
            throw new StorageException("Failed to read external dataset: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public String getExternalDatasetContentType(UUID externalDatasetId) {
        FormExternalDataset externalDataset = externalDatasetRepository.findById(externalDatasetId)
                .orElseThrow(() -> new ResourceNotFoundException("External dataset not found: " + externalDatasetId));
        return externalDataset.getContentType();
    }

    @Transactional(readOnly = true)
    public String getExternalDatasetDatasetId(UUID externalDatasetId) {
        FormExternalDataset externalDataset = externalDatasetRepository.findById(externalDatasetId)
                .orElseThrow(() -> new ResourceNotFoundException("External dataset not found: " + externalDatasetId));
        return externalDataset.getDatasetId();
    }

    @Transactional(readOnly = true)
    public long getExternalDatasetCount(UUID formVersionId) {
        return externalDatasetRepository.countByFormVersionId(formVersionId);
    }

    private void validateFile(MultipartFile file, String datasetId) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("File is empty");
        }

        if (file.getSize() > maxFileSize) {
            throw new ValidationException("File size exceeds maximum allowed size of " + maxFileSize + " bytes");
        }

        String originalFilename = file.getOriginalFilename();
        String extension = getFileExtension(
                originalFilename != null && !originalFilename.isBlank() ? originalFilename : datasetId);
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

    private String generateStoragePath(UUID formVersionId, String datasetId, String originalFilename) {
        String datePath = Instant.now().toString().substring(0, 10).replace("-", "/");
        String extension = getFileExtension(originalFilename != null ? originalFilename : datasetId);
        String suffix = extension.isEmpty() || datasetId.toLowerCase().endsWith("." + extension.toLowerCase())
                ? ""
                : "." + extension;
        String fileName = datasetId + suffix;
        return Paths.get("external-datasets", formVersionId.toString(), datePath, fileName).toString();
    }

    private FormExternalDatasetDto toDto(FormExternalDataset externalDataset) {
        return FormExternalDatasetDto.builder()
                .id(externalDataset.getId())
                .datasetId(externalDataset.getDatasetId())
                .title(externalDataset.getTitle())
                .description(externalDataset.getDescription())
                .contentType(externalDataset.getContentType())
                .storagePath(externalDataset.getStoragePath())
                .storageDriver(externalDataset.getStorageDriver())
                .contentHash(externalDataset.getContentHash())
                .fileSize(externalDataset.getFileSize())
                .rowCount(externalDataset.getRowCount())
                .columnDefinitions(externalDataset.getColumnDefinitions())
                .isActive(externalDataset.getIsActive())
                .formVersionId(externalDataset.getFormVersion().getId())
                .formVersionVersion(externalDataset.getFormVersion().getVersion())
                .createdById(externalDataset.getCreatedBy() != null ? externalDataset.getCreatedBy().getId() : null)
                .createdByUsername(externalDataset.getCreatedBy() != null ? externalDataset.getCreatedBy().getUsername() : null)
                .createdAt(externalDataset.getCreatedAt())
                .updatedAt(externalDataset.getUpdatedAt())
                .build();
    }
}