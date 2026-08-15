package org.odk.platform.repository;

import org.odk.platform.domain.AppUserProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppUserProjectRepository extends JpaRepository<AppUserProject, UUID> {

    @Query("SELECT aup FROM AppUserProject aup WHERE aup.appUser.id = :appUserId")
    List<AppUserProject> findByAppUserId(@Param("appUserId") UUID appUserId);

    @Query("SELECT aup FROM AppUserProject aup WHERE aup.project.id = :projectId")
    List<AppUserProject> findByProjectId(@Param("projectId") UUID projectId);

    @Query("SELECT aup FROM AppUserProject aup WHERE aup.appUser.id = :appUserId AND aup.project.id = :projectId")
    List<AppUserProject> findByAppUserIdAndProjectId(@Param("appUserId") UUID appUserId, @Param("projectId") UUID projectId);

    @Query("SELECT aup FROM AppUserProject aup WHERE aup.form.id = :formId")
    List<AppUserProject> findByFormId(@Param("formId") UUID formId);

    @Query("SELECT aup FROM AppUserProject aup WHERE aup.appUser.id = :appUserId AND aup.status = 'ACTIVE'")
    List<AppUserProject> findActiveByAppUserId(@Param("appUserId") UUID appUserId);

    @Query("SELECT aup.form.id FROM AppUserProject aup WHERE aup.appUser.id = :appUserId AND aup.project.id = :projectId AND aup.status = 'ACTIVE' AND aup.form IS NOT NULL")
    List<UUID> findAssignedFormIdsByAppUserIdAndProjectId(@Param("appUserId") UUID appUserId, @Param("projectId") UUID projectId);
}
