package org.odk.platform.repository;

import org.odk.platform.domain.FormStatus;
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
public interface FormVersionRepository extends JpaRepository<FormVersion, UUID> {

    @Query("SELECT fv FROM FormVersion fv WHERE fv.form.id = :formId ORDER BY fv.version DESC")
    List<FormVersion> findByFormIdOrderByVersionDesc(@Param("formId") UUID formId);

    @Query("SELECT fv FROM FormVersion fv WHERE fv.form.id = :formId AND fv.isPublished = true ORDER BY fv.version DESC")
    List<FormVersion> findPublishedByFormIdOrderByVersionDesc(@Param("formId") UUID formId);

    @Query("SELECT fv FROM FormVersion fv WHERE fv.form.id = :formId AND fv.isLatest = true")
    Optional<FormVersion> findLatestByFormId(@Param("formId") UUID formId);

    @Query("SELECT fv FROM FormVersion fv WHERE fv.form.id = :formId AND fv.version = :version")
    Optional<FormVersion> findByFormIdAndVersion(@Param("formId") UUID formId, @Param("version") String version);

    @Query("SELECT fv FROM FormVersion fv WHERE fv.id = :formVersionId")
    Optional<FormVersion> findByIdWithForm(@Param("formVersionId") UUID formVersionId);

    @Query("SELECT fv FROM FormVersion fv JOIN FETCH fv.form WHERE fv.id = :formVersionId")
    Optional<FormVersion> findByIdWithFormFetch(@Param("formVersionId") UUID formVersionId);

    @Query("SELECT fv FROM FormVersion fv WHERE fv.form.id = :formId AND fv.status = :status ORDER BY fv.version DESC")
    List<FormVersion> findByFormIdAndStatusOrderByVersionDesc(@Param("formId") UUID formId, @Param("status") FormStatus status);

    @Query("SELECT fv FROM FormVersion fv WHERE fv.form.id = :formId AND fv.status = :status ORDER BY fv.version DESC")
    Page<FormVersion> findByFormIdAndStatusOrderByVersionDesc(@Param("formId") UUID formId, @Param("status") FormStatus status, Pageable pageable);

    @Query("SELECT fv FROM FormVersion fv WHERE fv.status = :status ORDER BY fv.createdAt DESC")
    Page<FormVersion> findByStatusOrderByCreatedAtDesc(@Param("status") FormStatus status, Pageable pageable);

    @Query("SELECT fv FROM FormVersion fv WHERE fv.form.id = :formId AND fv.status IN :statuses ORDER BY fv.version DESC")
    List<FormVersion> findByFormIdAndStatusInOrderByVersionDesc(@Param("formId") UUID formId, @Param("statuses") List<FormStatus> statuses);

    Optional<FormVersion> findByFormIdAndIsLatestTrue(UUID formId);
}
