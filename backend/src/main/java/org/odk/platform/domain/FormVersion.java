package org.odk.platform.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "form_versions", indexes = {
    @Index(name = "idx_form_versions_form_id", columnList = "form_id"),
    @Index(name = "idx_form_versions_version", columnList = "version"),
    @Index(name = "idx_form_versions_is_published", columnList = "is_published"),
    @Index(name = "idx_form_versions_form_version", columnList = "form_id,version", unique = true),
    @Index(name = "idx_form_versions_created_at", columnList = "created_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormVersion {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "version", nullable = false, length = 50)
    private String version;

    @Column(name = "xml_content", columnDefinition = "TEXT")
    private String xmlContent;

    @Column(name = "xls_content", columnDefinition = "TEXT")
    private String xlsContent;

    @Column(name = "xmlns", length = 500)
    private String xmlns;

    @Column(name = "form_hash", length = 64)
    private String formHash;

    @Column(name = "is_published", nullable = false)
    @Builder.Default
    private Boolean isPublished = false;

    @Column(name = "is_latest", nullable = false)
    @Builder.Default
    private Boolean isLatest = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private FormStatus status = FormStatus.DRAFT;

    @Column(name = "changelog", columnDefinition = "TEXT")
    private String changelog;

    @Column(name = "settings", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private java.util.Map<String, Object> settings = new java.util.HashMap<>();

    @Column(name = "metadata", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private java.util.Map<String, Object> metadata = new java.util.HashMap<>();

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "validated_at")
    private Instant validatedAt;

    @Column(name = "deprecated_at")
    private Instant deprecatedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_id", nullable = false)
    private XForm form;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "published_by_id")
    private User publishedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "validated_by_id")
    private User validatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deprecated_by_id")
    private User deprecatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "archived_by_id")
    private User archivedBy;

    @OneToMany(mappedBy = "formVersion", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<Submission> submissions = new HashSet<>();

    @OneToMany(mappedBy = "formVersion", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<FormAttachment> attachments = new HashSet<>();

    @OneToMany(mappedBy = "formVersion", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<FormExternalDataset> externalDatasets = new HashSet<>();

    @OneToMany(mappedBy = "formVersion", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<FormPublishHistory> publishHistory = new HashSet<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FormVersion that = (FormVersion) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}