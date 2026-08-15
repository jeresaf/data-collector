package org.odk.platform.repository;

import org.odk.platform.domain.EntityItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EntityItemRepository extends JpaRepository<EntityItem, UUID> {
    List<EntityItem> findByEntityListIdAndIsActiveTrueOrderByLabelAsc(UUID entityListId);
    Optional<EntityItem> findByEntityListIdAndEntityId(UUID entityListId, String entityId);
    boolean existsByEntityListIdAndEntityId(UUID entityListId, String entityId);
}
