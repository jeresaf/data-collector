package org.odk.platform.service;

import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.ProjectSettings;
import org.odk.platform.domain.User;
import org.odk.platform.domain.UserPrincipal;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.ProjectSettingsRepository;
import org.odk.platform.repository.UserRepository;
import org.odk.platform.security.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class ProjectService {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectSettingsRepository projectSettingsRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CurrentUserService currentUserService;

    @Autowired
    private AuditService auditService;

    @Transactional(readOnly = true)
    public UUID getCurrentUserId() {
        return currentUserService.getCurrentUser()
                .map(UserPrincipal::getId)
                .orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }

    @Transactional(readOnly = true)
    public User getCurrentUser() {
        return currentUserService.getCurrentUser()
                .map(principal -> userRepository.findById(principal.getId())
                        .orElseThrow(() -> new IllegalStateException("User not found: " + principal.getId())))
                .orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }

    public Project createProject(String name, String description) {
        User currentUser = getCurrentUser();

        Project project = Project.builder()
                .name(name)
                .description(description)
                .isActive(true)
                .owner(currentUser)
                .createdBy(currentUser)
                .build();

        Project savedProject = projectRepository.save(project);
        
        // Audit log: Project created
        auditService.log(
                "PROJECT_CREATED",
                "PROJECT",
                savedProject.getId(),
                savedProject.getName(),
                Map.of(
                        "name", savedProject.getName(),
                        "description", savedProject.getDescription(),
                        "isActive", savedProject.getIsActive()
                )
        );
        
        return savedProject;
    }

    @Transactional(readOnly = true)
    public Optional<Project> findById(UUID projectId) {
        return projectRepository.findById(projectId);
    }

    @Transactional(readOnly = true)
    public Optional<Project> findByIdWithOwner(UUID projectId) {
        return projectRepository.findByIdWithOwner(projectId);
    }

    @Transactional(readOnly = true)
    public Optional<Project> findByIdWithOwnerAndCreatedBy(UUID projectId) {
        return projectRepository.findByIdWithOwnerAndCreatedBy(projectId);
    }

    @Transactional(readOnly = true)
    public List<Project> findByOwnerId(UUID ownerId) {
        return projectRepository.findByOwnerIdAndIsActiveTrue(ownerId);
    }

    @Transactional(readOnly = true)
    public List<Project> findByCreatedById(UUID createdById) {
        return projectRepository.findByCreatedByIdAndIsActiveTrue(createdById);
    }

    @Transactional(readOnly = true)
    public List<Project> findByMemberUserId(UUID userId) {
        return projectRepository.findByMemberUserIdAndIsActiveTrue(userId);
    }

    @Transactional(readOnly = true)
    public Page<Project> findAll(Pageable pageable) {
        return projectRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<Project> findByOwnerId(UUID ownerId, Pageable pageable) {
        return projectRepository.findByOwnerIdAndIsActiveTrue(ownerId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Project> findByMemberUserId(UUID userId, Pageable pageable) {
        return projectRepository.findByMemberUserIdAndIsActiveTrue(userId, pageable);
    }

    public Project updateProject(UUID projectId, String name, String description, Boolean isActive) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        // Capture old values for audit
        String oldName = project.getName();
        String oldDescription = project.getDescription();
        Boolean oldIsActive = project.getIsActive();

        if (name != null) {
            project.setName(name);
        }
        if (description != null) {
            project.setDescription(description);
        }
        if (isActive != null) {
            project.setIsActive(isActive);
        }

        User currentUser = getCurrentUser();
        project.setUpdatedBy(currentUser);

        Project savedProject = projectRepository.save(project);
        
        // Audit log: Project updated
        auditService.log(
                "PROJECT_UPDATED",
                "PROJECT",
                savedProject.getId(),
                savedProject.getName(),
                Map.of(
                        "oldName", oldName,
                        "newName", savedProject.getName(),
                        "oldDescription", oldDescription,
                        "newDescription", savedProject.getDescription(),
                        "oldIsActive", oldIsActive,
                        "newIsActive", savedProject.getIsActive()
                )
        );
        
        return savedProject;
    }

    public void archiveProject(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        project.setIsActive(false);
        User currentUser = getCurrentUser();
        project.setUpdatedBy(currentUser);

        projectRepository.save(project);
        
        // Audit log: Project archived
        auditService.log(
                "PROJECT_ARCHIVED",
                "PROJECT",
                projectId,
                project.getName(),
                Map.of(
                        "name", project.getName(),
                        "description", project.getDescription()
                )
        );
    }

    public void deleteProject(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        
        String projectName = project.getName();
        String projectDescription = project.getDescription();
        
        projectRepository.delete(project);
        
        // Audit log: Project deleted
        auditService.log(
                "PROJECT_DELETED",
                "PROJECT",
                projectId,
                projectName,
                Map.of(
                        "name", projectName,
                        "description", projectDescription
                )
        );
    }

    // Project Settings methods
    public ProjectSettings getOrCreateSetting(UUID projectId, String settingKey) {
        return projectSettingsRepository.findByProjectIdAndSettingKey(projectId, settingKey)
                .orElseGet(() -> {
                    ProjectSettings settings = new ProjectSettings();
                    settings.setProject(projectRepository.getReferenceById(projectId));
                    settings.setSettingKey(settingKey);
                    return settings;
                });
    }

    public ProjectSettings updateSetting(UUID projectId, String settingKey, Map<String, Object> settingValue, String description) {
        ProjectSettings settings = getOrCreateSetting(projectId, settingKey);
        
        // Capture old value for audit
        Map<String, Object> oldValue = settings.getSettingValue();
        String oldDescription = settings.getDescription();
        
        settings.setSettingValue(settingValue);
        if (description != null) {
            settings.setDescription(description);
        }
        User currentUser = getCurrentUser();
        settings.setUpdatedBy(currentUser);
        
        ProjectSettings savedSettings = projectSettingsRepository.save(settings);
        
        // Audit log: Project setting updated
        Map<String, Object> auditData = new java.util.HashMap<>();
        auditData.put("settingKey", settingKey);
        auditData.put("newValue", savedSettings.getSettingValue());
        auditData.put("newDescription", savedSettings.getDescription());
        if (oldValue != null) {
            auditData.put("oldValue", oldValue);
        }
        if (oldDescription != null) {
            auditData.put("oldDescription", oldDescription);
        }
        auditService.log(
                "PROJECT_SETTING_UPDATED",
                "PROJECT_SETTING",
                projectId,
                settingKey,
                auditData
        );
        
        return savedSettings;
    }

    @Transactional(readOnly = true)
    public Optional<ProjectSettings> getSetting(UUID projectId, String settingKey) {
        return projectSettingsRepository.findByProjectIdAndSettingKey(projectId, settingKey);
    }

    @Transactional(readOnly = true)
    public List<ProjectSettings> getAllSettings(UUID projectId) {
        return projectSettingsRepository.findByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAllSettingsAsMap(UUID projectId) {
        return projectSettingsRepository.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(
                        ProjectSettings::getSettingKey,
                        ProjectSettings::getSettingValue
                ));
    }

    public void deleteSetting(UUID projectId, String settingKey) {
        // Get the setting before deleting for audit log
        Optional<ProjectSettings> settingOpt = projectSettingsRepository.findByProjectIdAndSettingKey(projectId, settingKey);
        
        // If setting doesn't exist, do nothing (no audit log)
        if (settingOpt.isEmpty()) {
            return;
        }
        
        Map<String, Object> oldValue = settingOpt.map(ProjectSettings::getSettingValue).orElse(null);
        String oldDescription = settingOpt.map(ProjectSettings::getDescription).orElse(null);
        
        projectSettingsRepository.deleteByProjectIdAndSettingKey(projectId, settingKey);
        
        // Audit log: Project setting deleted
        User currentUser = getCurrentUser();
        Map<String, Object> auditData = new java.util.HashMap<>();
        auditData.put("settingKey", settingKey);
        if (oldValue != null) {
            auditData.put("oldValue", oldValue);
        }
        if (oldDescription != null) {
            auditData.put("oldDescription", oldDescription);
        }
        auditService.log(
                "PROJECT_SETTING_DELETED",
                "PROJECT_SETTING",
                projectId,
                settingKey,
                auditData
        );
    }

    public void deleteAllSettings(UUID projectId) {
        // Get all settings before deleting for audit log
        List<ProjectSettings> settings = projectSettingsRepository.findByProjectId(projectId);
        Map<String, Object> settingsMap = settings.stream()
                .collect(Collectors.toMap(
                        ProjectSettings::getSettingKey,
                        s -> Map.of(
                                "value", s.getSettingValue(),
                                "description", s.getDescription()
                        )
                ));
        
        projectSettingsRepository.deleteByProjectId(projectId);
        
        // Audit log: All project settings deleted
        User currentUser = getCurrentUser();
        auditService.log(
                "PROJECT_SETTINGS_DELETED",
                "PROJECT_SETTING",
                projectId,
                "all_settings",
                Map.of(
                        "deletedSettings", settingsMap
                )
        );
    }

    // Default settings initialization
    public void initializeDefaultSettings(UUID projectId, User createdBy) {
        Project project = projectRepository.getReferenceById(projectId);
        
        Map<String, Object> defaultSettings = Map.of(
                "allow_anonymous_submissions", Map.of("value", false, "description", "Allow anonymous form submissions without authentication"),
                "require_review_before_approval", Map.of("value", false, "description", "Require admin review before submissions are approved"),
                "default_form_status", Map.of("value", "draft", "description", "Default status for new forms (draft, validated, published)"),
                "max_submission_size_mb", Map.of("value", 10, "description", "Maximum submission size in megabytes"),
                "timezone", Map.of("value", "UTC", "description", "Default timezone for the project"),
                "supported_languages", Map.of("value", List.of("en"), "description", "Supported languages for forms in this project")
        );

        Map<String, Object> initializedSettings = new java.util.HashMap<>();
        
        defaultSettings.forEach((key, value) -> {
            if (!projectSettingsRepository.existsByProjectIdAndSettingKey(projectId, key)) {
                ProjectSettings settings = ProjectSettings.builder()
                        .project(project)
                        .settingKey(key)
                        .settingValue((Map<String, Object>) value)
                        .description((String) ((Map<?, ?>) value).get("description"))
                        .createdBy(createdBy)
                        .updatedBy(createdBy)
                        .build();
                projectSettingsRepository.save(settings);
                initializedSettings.put(key, value);
            }
        });
        
        // Audit log: Default settings initialized
        if (!initializedSettings.isEmpty()) {
            auditService.log(
                    "PROJECT_SETTINGS_INITIALIZED",
                    "PROJECT_SETTING",
                    projectId,
                    "default_settings",
                    Map.of(
                            "initializedSettings", initializedSettings
                    )
            );
        }
    }
}