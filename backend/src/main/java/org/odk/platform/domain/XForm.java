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
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "xforms", indexes = {
    @Index(name = "idx_xforms_project_id", columnList = "project_id"),
    @Index(name = "idx_xforms_created_by_id", columnList = "created_by_id"),
    @Index(name = "idx_xforms_is_published", columnList = "is_published"),
    @Index(name = "idx_xforms_is_active", columnList = "is_active"),
    @Index(name = "idx_xforms_xmlns", columnList = "xmlns"),
    @Index(name = "idx_xforms_created_at", columnList = "created_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class XForm {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "xmlns", length = 500)
    private String xmlns;

    @Column(name = "version", length = 50)
    private String version;

    @Column(name = "xml_content", columnDefinition = "TEXT")
    private String xmlContent;

    @Column(name = "xls_content", columnDefinition = "TEXT")
    private String xlsContent;

    @Column(name = "is_published", nullable = false)
    @Builder.Default
    private Boolean isPublished = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "is_encrypted", nullable = false)
    @Builder.Default
    private Boolean isEncrypted = false;

    @Column(name = "encryption_key", length = 500)
    private String encryptionKey;

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

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_id")
    private User updatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "published_by_id")
    private User publishedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_version_id")
    private FormVersion currentVersion;

    @OneToMany(mappedBy = "form", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<FormVersion> versions = new HashSet<>();

    @OneToMany(mappedBy = "form", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<Submission> submissions = new HashSet<>();

    @OneToMany(mappedBy = "form", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<AppUserProject> appUserProjects = new HashSet<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        XForm xForm = (XForm) o;
        return id != null && id.equals(xForm.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}