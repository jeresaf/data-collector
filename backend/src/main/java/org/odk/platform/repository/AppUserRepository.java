package org.odk.platform.repository;

import org.odk.platform.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    @Query("SELECT au FROM AppUser au JOIN FETCH au.appUserProjects WHERE au.id = :id")
    Optional<AppUser> findByIdWithProjects(@Param("id") UUID id);

    @Query("SELECT au FROM AppUser au JOIN FETCH au.appUserProjects WHERE au.username = :username")
    Optional<AppUser> findByUsernameWithProjects(@Param("username") String username);

    List<AppUser> findByIsActiveTrue();

    @Query("SELECT DISTINCT au FROM AppUser au JOIN au.appUserProjects aup WHERE aup.project.id = :projectId")
    List<AppUser> findByProjectId(@Param("projectId") UUID projectId);
}
