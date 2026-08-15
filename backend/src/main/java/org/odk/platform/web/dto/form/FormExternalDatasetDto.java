package org.odk.platform.web.dto.form;

import lombok.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormExternalDatasetDto {

    private UUID id;
    private String datasetId;
    private String title;
    private String description;
    private String contentType;
    private String storagePath;
    private String storageDriver;
    private String contentHash;
    private Long fileSize;
    private Long rowCount;
    private Map<String, Object> columnDefinitions;
    private Boolean isActive;
    private UUID formVersionId;
    private String formVersionVersion;
    private UUID createdById;
    private String createdByUsername;
    private Instant createdAt;
    private Instant updatedAt;
}