package org.odk.platform.repository;

import org.odk.platform.domain.Project;
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
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    @Query("SELECT p FROM Project p WHERE p.owner.id = :ownerId AND p.isActive = true")
    List<Project> findByOwnerIdAndIsActiveTrue(@Param("ownerId") UUID ownerId);

    @Query("SELECT p FROM Project p WHERE p.owner.id = :ownerId AND p.isActive = true")
    Page<Project> findByOwnerIdAndIsActiveTrue(@Param("ownerId") UUID ownerId, Pageable pageable);

    @Query("SELECT p FROM Project p WHERE p.createdBy.id = :createdById AND p.isActive = true")
    List<Project> findByCreatedByIdAndIsActiveTrue(@Param("createdById") UUID createdById);

    @Query("SELECT p FROM Project p JOIN p.members m WHERE m.user.id = :userId AND p.isActive = true AND m.isActive = true")
    List<Project> findByMemberUserIdAndIsActiveTrue(@Param("userId") UUID userId);

    @Query("SELECT p FROM Project p JOIN p.members m WHERE m.user.id = :userId AND p.isActive = true AND m.isActive = true")
    Page<Project> findByMemberUserIdAndIsActiveTrue(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT p FROM Project p WHERE p.id = :projectId AND p.isActive = true")
    Optional<Project> findByIdAndIsActiveTrue(@Param("projectId") UUID projectId);

    @Query("SELECT p FROM Project p JOIN FETCH p.owner WHERE p.id = :projectId")
    Optional<Project> findByIdWithOwner(@Param("projectId") UUID projectId);

    @Query("SELECT p FROM Project p JOIN FETCH p.owner JOIN FETCH p.createdBy WHERE p.id = :projectId")
    Optional<Project> findByIdWithOwnerAndCreatedBy(@Param("projectId") UUID projectId);
}
