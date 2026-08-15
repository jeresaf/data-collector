package org.odk.platform.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "form_external_datasets", indexes = {
    @Index(name = "idx_form_external_datasets_form_version_id", columnList = "form_version_id"),
    @Index(name = "idx_form_external_datasets_dataset_id", columnList = "dataset_id"),
    @Index(name = "idx_form_external_datasets_content_hash", columnList = "content_hash"),
    @Index(name = "idx_form_external_datasets_is_active", columnList = "is_active"),
    @Index(name = "idx_form_external_datasets_created_at", columnList = "created_at"),
    @Index(name = "idx_form_external_datasets_form_version_dataset", columnList = "form_version_id,dataset_id", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormExternalDataset {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "dataset_id", nullable = false, length = 255)
    private String datasetId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "storage_path", nullable = false, length = 500)
    private String storagePath;

    @Column(name = "storage_driver", nullable = false, length = 50)
    @Builder.Default
    private String storageDriver = "local";

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "column_definitions", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private Map<String, Object> columnDefinitions = new java.util.HashMap<>();

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_version_id", nullable = false)
    private FormVersion formVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FormExternalDataset that = (FormExternalDataset) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}