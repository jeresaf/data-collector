package org.odk.platform.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** A single item in a generic ODK Entity List. */
@Entity
@Table(name = "entity_items", indexes = {
        @Index(name = "idx_entity_items_entity_list_id", columnList = "entity_list_id"),
        @Index(name = "idx_entity_items_entity_id", columnList = "entity_id"),
        @Index(name = "idx_entity_items_is_active", columnList = "is_active"),
        @Index(name = "idx_entity_items_list_entity", columnList = "entity_list_id,entity_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EntityItem {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entity_list_id", nullable = false)
    private EntityList entityList;

    /** Value exposed as the standard ODK dataset {@code name} column. */
    @Column(name = "entity_id", nullable = false, length = 255)
    private String entityId;

    /** Human-readable value exposed as the standard ODK dataset {@code label} column. */
    @Column(name = "label", nullable = false, columnDefinition = "TEXT")
    private String label;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "entityItem", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<EntityItemProperty> properties = new LinkedHashSet<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EntityItem that = (EntityItem) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
