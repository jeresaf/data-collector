package org.odk.platform.domain;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonManagedReference;
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
@Table(name = "projects", indexes = {
    @Index(name = "idx_projects_owner_id", columnList = "owner_id"),
    @Index(name = "idx_projects_created_by_id", columnList = "created_by_id"),
    @Index(name = "idx_projects_is_active", columnList = "is_active"),
    @Index(name = "idx_projects_created_at", columnList = "created_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "settings", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private java.util.Map<String, Object> settings = new java.util.HashMap<>();

    @Column(name = "metadata", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private java.util.Map<String, Object> metadata = new java.util.HashMap<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    @JsonIgnoreProperties({"createdProjects", "ownedProjects", "createdForms", "reviewedSubmissions", "auditLogs", "refreshTokens", "passwordResetTokens", "emailVerificationTokens", "userRoles", "assignedRoles", "roles", "projectSettings", "hibernateLazyInitializer", "handler"})
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    @JsonIgnoreProperties({"createdProjects", "ownedProjects", "createdForms", "reviewedSubmissions", "auditLogs", "refreshTokens", "passwordResetTokens", "emailVerificationTokens", "userRoles", "assignedRoles", "roles", "projectSettings", "hibernateLazyInitializer", "handler"})
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_id")
    @JsonIgnoreProperties({"createdProjects", "ownedProjects", "createdForms", "reviewedSubmissions", "auditLogs", "refreshTokens", "passwordResetTokens", "emailVerificationTokens", "userRoles", "assignedRoles", "roles", "projectSettings", "hibernateLazyInitializer", "handler"})
    private User updatedBy;

    @OneToMany(mappedBy = "project", fetch = FetchType.LAZY)
    @Builder.Default
    @JsonIgnoreProperties({"project", "user", "role", "assignedBy", "hibernateLazyInitializer", "handler"})
    private Set<ProjectMember> members = new HashSet<>();

    @OneToMany(mappedBy = "project", fetch = FetchType.LAZY)
    @Builder.Default
    @JsonIgnoreProperties({"project", "createdBy", "updatedBy", "publishedBy", "versions", "submissions", "appUserProjects", "hibernateLazyInitializer", "handler"})
    private Set<XForm> forms = new HashSet<>();

    @OneToMany(mappedBy = "project", fetch = FetchType.LAZY)
    @Builder.Default
    @JsonIgnoreProperties({"project", "user", "role", "assignedBy", "hibernateLazyInitializer", "handler"})
    private Set<UserRole> userRoles = new HashSet<>();

    @OneToMany(mappedBy = "project", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @JsonManagedReference("project-settings")
    private Set<ProjectSettings> projectSettings = new HashSet<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Project project = (Project) o;
        return id != null && id.equals(project.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}