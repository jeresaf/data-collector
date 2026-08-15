package org.odk.platform.repository;

import org.odk.platform.domain.ReferenceItemProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReferenceItemPropertyRepository extends JpaRepository<ReferenceItemProperty, UUID> {
    List<ReferenceItemProperty> findByReferenceItemIdOrderByPropertyNameAsc(UUID referenceItemId);
    void deleteByReferenceItemId(UUID referenceItemId);
}
