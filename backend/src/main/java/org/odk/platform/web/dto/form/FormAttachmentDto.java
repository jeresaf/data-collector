package org.odk.platform.web.dto.form;

import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormAttachmentDto {

    private UUID id;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private String storagePath;
    private String storageDriver;
    private String contentHash;
    private Boolean isEncrypted;
    private UUID formVersionId;
    private String formVersionVersion;
    private UUID createdById;
    private String createdByUsername;
    private Instant createdAt;
    private Instant updatedAt;
}