package org.odk.platform.repository;

import org.odk.platform.domain.ReferenceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReferenceItemRepository extends JpaRepository<ReferenceItem, UUID> {
    List<ReferenceItem> findByReferenceListIdOrderBySortOrderAscLabelAsc(UUID referenceListId);
    Optional<ReferenceItem> findByIdAndReferenceListId(UUID id, UUID referenceListId);
    Optional<ReferenceItem> findByReferenceListIdAndItemKey(UUID referenceListId, String itemKey);
    boolean existsByReferenceListIdAndItemKey(UUID referenceListId, String itemKey);
}
