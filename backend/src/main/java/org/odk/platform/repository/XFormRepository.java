package org.odk.platform.repository;

import org.odk.platform.domain.XForm;
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
public interface XFormRepository extends JpaRepository<XForm, UUID> {

    @Query("SELECT f FROM XForm f WHERE f.project.id = :projectId AND f.isActive = true")
    List<XForm> findByProjectIdAndIsActiveTrue(@Param("projectId") UUID projectId);

    @Query("SELECT f FROM XForm f WHERE f.project.id = :projectId AND f.isActive = true")
    Page<XForm> findByProjectIdAndIsActiveTrue(@Param("projectId") UUID projectId, Pageable pageable);

    @Query("SELECT f FROM XForm f WHERE f.project.id = :projectId AND f.isPublished = true AND f.isActive = true")
    List<XForm> findPublishedByProjectId(@Param("projectId") UUID projectId);

    @Query("SELECT f FROM XForm f WHERE f.project.id = :projectId AND f.isPublished = true AND f.isActive = true AND f.id IN :formIds")
    List<XForm> findPublishedByProjectIdAndFormIds(@Param("projectId") UUID projectId, @Param("formIds") List<UUID> formIds);

    @Query("SELECT f FROM XForm f WHERE f.id = :formId AND f.project.id = :projectId")
    Optional<XForm> findByIdAndProjectId(@Param("formId") UUID formId, @Param("projectId") UUID projectId);

    @Query("SELECT f FROM XForm f WHERE f.xmlns = :xmlns AND f.project.id = :projectId")
    Optional<XForm> findByXmlnsAndProjectId(@Param("xmlns") String xmlns, @Param("projectId") UUID projectId);

    @Query("SELECT f FROM XForm f WHERE f.id = :formId")
    Optional<XForm> findByIdWithProject(@Param("formId") UUID formId);

    @Query("SELECT f FROM XForm f JOIN FETCH f.project WHERE f.id = :formId")
    Optional<XForm> findByIdWithProjectFetch(@Param("formId") UUID formId);
}
