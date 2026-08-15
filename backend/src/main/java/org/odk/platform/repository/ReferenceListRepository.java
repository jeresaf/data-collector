package org.odk.platform.repository;

import org.odk.platform.domain.ReferenceList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReferenceListRepository extends JpaRepository<ReferenceList, UUID> {
    List<ReferenceList> findByProjectIdOrderByNameAsc(UUID projectId);
    Optional<ReferenceList> findByIdAndProjectId(UUID id, UUID projectId);
    Optional<ReferenceList> findByProjectIdAndNameIgnoreCase(UUID projectId, String name);
    boolean existsByProjectIdAndNameIgnoreCase(UUID projectId, String name);
}
