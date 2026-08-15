package org.odk.platform.repository;

import org.odk.platform.domain.ProjectSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectSettingsRepository extends JpaRepository<ProjectSettings, UUID> {

    Optional<ProjectSettings> findByProjectIdAndSettingKey(UUID projectId, String settingKey);

    List<ProjectSettings> findByProjectId(UUID projectId);

    @Query("SELECT ps FROM ProjectSettings ps WHERE ps.project.id = :projectId AND ps.settingKey = :settingKey")
    Optional<ProjectSettings> findByProjectIdAndSettingKeyNative(@Param("projectId") UUID projectId, @Param("settingKey") String settingKey);

    void deleteByProjectId(UUID projectId);

    void deleteByProjectIdAndSettingKey(UUID projectId, String settingKey);

    boolean existsByProjectIdAndSettingKey(UUID projectId, String settingKey);
}
