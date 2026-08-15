package org.odk.platform.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Generic directed relationship between two reference items. */
@Entity
@Table(name = "reference_item_relationships", indexes = {
        @Index(name = "idx_reference_relationships_parent", columnList = "parent_item_id"),
        @Index(name = "idx_reference_relationships_child", columnList = "child_item_id"),
        @Index(name = "idx_reference_relationships_type", columnList = "relationship_type"),
        @Index(name = "idx_reference_relationships_unique", columnList = "parent_item_id,child_item_id,relationship_type", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReferenceItemRelationship {
    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parent_item_id", nullable = false)
    private ReferenceItem parentItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "child_item_id", nullable = false)
    private ReferenceItem childItem;

    @Column(name = "relationship_type", nullable = false, length = 100)
    @Builder.Default
    private String relationshipType = "parent";

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
