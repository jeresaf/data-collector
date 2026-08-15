package org.odk.platform.web.dto.form;

import lombok.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormExternalDatasetUploadRequest {

    @NotBlank(message = "Dataset ID is required")
    private String datasetId;

    @NotBlank(message = "Title is required")
    private String title;

    private String description;

    @NotNull(message = "Storage driver is required")
    private String storageDriver;

    private Long rowCount;

    private Map<String, Object> columnDefinitions;

    private Boolean isActive;
}