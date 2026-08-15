package org.odk.platform.repository;

import org.odk.platform.domain.FormExternalDataset;
import org.odk.platform.domain.FormVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FormExternalDatasetRepository extends JpaRepository<FormExternalDataset, UUID> {

    List<FormExternalDataset> findByFormVersionId(UUID formVersionId);

    Page<FormExternalDataset> findByFormVersionId(UUID formVersionId, Pageable pageable);

    List<FormExternalDataset> findByFormVersionIdAndIsActiveTrue(UUID formVersionId);

    Optional<FormExternalDataset> findByFormVersionIdAndDatasetId(UUID formVersionId, String datasetId);

    List<FormExternalDataset> findByContentHash(String contentHash);

    boolean existsByFormVersionIdAndDatasetId(UUID formVersionId, String datasetId);

    long countByFormVersionId(UUID formVersionId);

    @Query("SELECT fed FROM FormExternalDataset fed WHERE fed.formVersion.id = :formVersionId AND fed.isActive = true ORDER BY fed.createdAt DESC")
    List<FormExternalDataset> findActiveByFormVersionIdOrderByCreatedAtDesc(@Param("formVersionId") UUID formVersionId);
}