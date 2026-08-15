package org.odk.platform.web.dto.form;

import lombok.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormAttachmentUploadRequest {

    @NotBlank(message = "File name is required")
    private String fileName;

    @NotNull(message = "Storage driver is required")
    private String storageDriver;

    private Boolean isEncrypted;

    private String encryptionKey;
}