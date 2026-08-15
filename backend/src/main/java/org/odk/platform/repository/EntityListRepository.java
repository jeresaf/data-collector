package org.odk.platform.repository;

import org.odk.platform.domain.EntityList;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EntityListRepository extends JpaRepository<EntityList, UUID> {

    @EntityGraph(attributePaths = {"items", "items.properties"})
    Optional<EntityList> findByProjectIdAndNameAndIsActiveTrue(UUID projectId, String name);

    List<EntityList> findByProjectIdAndIsActiveTrueOrderByNameAsc(UUID projectId);

    boolean existsByProjectIdAndName(UUID projectId, String name);
}
