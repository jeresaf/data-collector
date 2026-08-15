package org.odk.platform.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Flexible key/value property attached to an Entity item. */
@Entity
@Table(name = "entity_item_properties", indexes = {
        @Index(name = "idx_entity_item_properties_item_id", columnList = "entity_item_id"),
        @Index(name = "idx_entity_item_properties_name", columnList = "property_name"),
        @Index(name = "idx_entity_item_properties_item_name", columnList = "entity_item_id,property_name", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EntityItemProperty {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entity_item_id", nullable = false)
    private EntityItem entityItem;

    @Column(name = "property_name", nullable = false, length = 255)
    private String propertyName;

    @Column(name = "property_value", columnDefinition = "TEXT")
    private String propertyValue;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EntityItemProperty that = (EntityItemProperty) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
