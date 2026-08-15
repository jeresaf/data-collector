package org.odk.platform.repository;

import org.odk.platform.domain.ReferenceItemRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReferenceItemRelationshipRepository extends JpaRepository<ReferenceItemRelationship, UUID> {
    List<ReferenceItemRelationship> findByChildItemIdOrderBySortOrderAsc(UUID childItemId);
    List<ReferenceItemRelationship> findByParentItemIdOrderBySortOrderAsc(UUID parentItemId);
    Optional<ReferenceItemRelationship> findByParentItemIdAndChildItemIdAndRelationshipType(
            UUID parentItemId, UUID childItemId, String relationshipType);
    void deleteByChildItemId(UUID childItemId);
    void deleteByParentItemId(UUID parentItemId);
}
