package org.odk.platform.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Flexible key/value metadata attached to a reference item. */
@Entity
@Table(name = "reference_item_properties", indexes = {
        @Index(name = "idx_reference_item_properties_item_id", columnList = "reference_item_id"),
        @Index(name = "idx_reference_item_properties_name", columnList = "property_name"),
        @Index(name = "idx_reference_item_properties_item_name", columnList = "reference_item_id,property_name", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReferenceItemProperty {
    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reference_item_id", nullable = false)
    private ReferenceItem referenceItem;

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
}
