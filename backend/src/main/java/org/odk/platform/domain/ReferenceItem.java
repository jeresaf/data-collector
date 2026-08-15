package org.odk.platform.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** A single item in a generic reference list. */
@Entity
@Table(name = "reference_items", indexes = {
        @Index(name = "idx_reference_items_list_id", columnList = "reference_list_id"),
        @Index(name = "idx_reference_items_item_key", columnList = "item_key"),
        @Index(name = "idx_reference_items_is_active", columnList = "is_active"),
        @Index(name = "idx_reference_items_list_key", columnList = "reference_list_id,item_key", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReferenceItem {
    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reference_list_id", nullable = false)
    private ReferenceList referenceList;

    @Column(name = "item_key", nullable = false, length = 255)
    private String itemKey;

    @Column(name = "label", nullable = false, columnDefinition = "TEXT")
    private String label;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "referenceItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<ReferenceItemProperty> properties = new LinkedHashSet<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReferenceItem that = (ReferenceItem) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
