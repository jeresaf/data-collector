package org.odk.platform.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.ProjectSettings;
import org.odk.platform.domain.User;
import org.odk.platform.domain.UserPrincipal;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.ProjectSettingsRepository;
import org.odk.platform.repository.UserRepository;
import org.odk.platform.security.CurrentUserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectSettingsRepository projectSettingsRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private ProjectService projectService;

    private User testUser;
    private UserPrincipal testUserPrincipal;
    private Project testProject;
    private UUID userId;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        projectId = UUID.randomUUID();

        testUser = User.builder()
                .id(userId)
                .username("testuser")
                .email("test@example.com")
                .firstName("Test")
                .lastName("User")
                .build();

        testUserPrincipal = mock(UserPrincipal.class);
        when(testUserPrincipal.getId()).thenReturn(userId);
        when(testUserPrincipal.getUsername()).thenReturn("testuser");
        when(testUserPrincipal.getEmail()).thenReturn("test@example.com");
        when(testUserPrincipal.getFullName()).thenReturn("Test User");
        when(testUserPrincipal.isActive()).thenReturn(true);
        when(testUserPrincipal.isVerified()).thenReturn(true);

        testProject = Project.builder()
                .id(projectId)
                .name("Test Project")
                .description("Test Description")
                .isActive(true)
                .owner(testUser)
                .createdBy(testUser)
                .build();
    }

    @Test
    void createProject_ShouldCreateProjectAndAudit() {
        // Given
        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(testUserPrincipal));
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project p = invocation.getArgument(0);
            // Simulate DB-generated ID
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            return p;
        });

        // When
        Project created = projectService.createProject("New Project", "New Description");

        // Then
        assertThat(created).isNotNull();
        assertThat(created.getName()).isEqualTo("New Project");
        assertThat(created.getDescription()).isEqualTo("New Description");
        assertThat(created.getIsActive()).isTrue();
        assertThat(created.getOwner()).isEqualTo(testUser);
        assertThat(created.getCreatedBy()).isEqualTo(testUser);
        assertThat(created.getId()).isNotNull();

        verify(projectRepository).save(any(Project.class));
        verify(auditService).log(eq("PROJECT_CREATED"), eq("PROJECT"), any(UUID.class), eq("New Project"), any(Map.class));
    }

    @Test
    void createProject_WhenNoAuthenticatedUser_ShouldThrowException() {
        // Given
        when(currentUserService.getCurrentUser()).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> projectService.createProject("New Project", "New Description"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No authenticated user");

        verify(projectRepository, never()).save(any());
        verify(auditService, never()).log(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void findById_ShouldReturnProject() {
        // Given
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(testProject));

        // When
        Optional<Project> result = projectService.findById(projectId);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(testProject);
    }

    @Test
    void findById_WhenNotFound_ShouldReturnEmpty() {
        // Given
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        // When
        Optional<Project> result = projectService.findById(projectId);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void findByOwnerId_ShouldReturnProjects() {
        // Given
        Page<Project> page = new PageImpl<>(List.of(testProject));
        when(projectRepository.findByOwnerIdAndIsActiveTrue(eq(userId), any(Pageable.class))).thenReturn(page);

        // When
        Page<Project> result = projectService.findByOwnerId(userId, Pageable.unpaged());

        // Then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0)).isEqualTo(testProject);
    }

    @Test
    void findByMemberUserId_ShouldReturnProjects() {
        // Given
        Page<Project> page = new PageImpl<>(List.of(testProject));
        when(projectRepository.findByMemberUserIdAndIsActiveTrue(eq(userId), any(Pageable.class))).thenReturn(page);

        // When
        Page<Project> result = projectService.findByMemberUserId(userId, Pageable.unpaged());

        // Then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0)).isEqualTo(testProject);
    }

    @Test
    void updateProject_ShouldUpdateAndAudit() {
        // Given
        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(testUserPrincipal));
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(testProject));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        Project updated = projectService.updateProject(projectId, "Updated Name", "Updated Description", false);

        // Then
        assertThat(updated.getName()).isEqualTo("Updated Name");
        assertThat(updated.getDescription()).isEqualTo("Updated Description");
        assertThat(updated.getIsActive()).isFalse();
        assertThat(updated.getUpdatedBy()).isEqualTo(testUser);

        verify(projectRepository).save(any(Project.class));
        verify(auditService).log(eq("PROJECT_UPDATED"), eq("PROJECT"), eq(projectId), eq("Updated Name"), any(Map.class));
    }

    @Test
    void updateProject_WhenNotFound_ShouldThrowException() {
        // Given
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> projectService.updateProject(projectId, "New Name", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Project not found: " + projectId);

        verify(projectRepository, never()).save(any());
        verify(auditService, never()).log(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void updateProject_WhenNoAuthenticatedUser_ShouldThrowException() {
        // Given
        when(currentUserService.getCurrentUser()).thenReturn(Optional.empty());
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(testProject));

        // When & Then
        assertThatThrownBy(() -> projectService.updateProject(projectId, "New Name", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No authenticated user");
    }

    @Test
    void archiveProject_ShouldArchiveAndAudit() {
        // Given
        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(testUserPrincipal));
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(testProject));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        projectService.archiveProject(projectId);

        // Then
        verify(projectRepository).save(argThat(p -> p.getIsActive() == false && p.getUpdatedBy().equals(testUser)));
        verify(auditService).log(eq("PROJECT_ARCHIVED"), eq("PROJECT"), eq(projectId), eq("Test Project"), any(Map.class));
    }

    @Test
    void archiveProject_WhenNotFound_ShouldThrowException() {
        // Given
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> projectService.archiveProject(projectId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Project not found: " + projectId);
    }

    @Test
    void deleteProject_ShouldDeleteAndAudit() {
        // Given
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(testProject));
        doNothing().when(projectRepository).delete(testProject);

        // When
        projectService.deleteProject(projectId);

        // Then
        verify(projectRepository).delete(testProject);
        verify(auditService).log(eq("PROJECT_DELETED"), eq("PROJECT"), eq(projectId), eq("Test Project"), any(Map.class));
    }

    @Test
    void deleteProject_WhenNotFound_ShouldThrowException() {
        // Given
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> projectService.deleteProject(projectId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Project not found: " + projectId);
    }

    @Test
    void getOrCreateSetting_WhenExists_ShouldReturnExisting() {
        // Given
        ProjectSettings existing = new ProjectSettings();
        existing.setSettingKey("test_key");
        existing.setSettingValue(Map.of("value", "existing"));
        when(projectSettingsRepository.findByProjectIdAndSettingKey(projectId, "test_key"))
                .thenReturn(Optional.of(existing));

        // When
        ProjectSettings result = projectService.getOrCreateSetting(projectId, "test_key");

        // Then
        assertThat(result).isEqualTo(existing);
        assertThat(result.getSettingValue()).isEqualTo(Map.of("value", "existing"));
    }

    @Test
    void getOrCreateSetting_WhenNotExists_ShouldCreateNew() {
        // Given
        when(projectSettingsRepository.findByProjectIdAndSettingKey(projectId, "new_key"))
                .thenReturn(Optional.empty());
        when(projectRepository.getReferenceById(projectId)).thenReturn(testProject);

        // When
        ProjectSettings result = projectService.getOrCreateSetting(projectId, "new_key");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getSettingKey()).isEqualTo("new_key");
        assertThat(result.getProject()).isEqualTo(testProject);
    }

    @Test
    void updateSetting_ShouldUpdateAndAudit() {
        // Given
        ProjectSettings existing = new ProjectSettings();
        existing.setSettingKey("test_key");
        existing.setSettingValue(Map.of("value", "old"));
        existing.setDescription("Old description");

        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(testUserPrincipal));
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(projectSettingsRepository.findByProjectIdAndSettingKey(projectId, "test_key"))
                .thenReturn(Optional.of(existing));
        when(projectSettingsRepository.save(any(ProjectSettings.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        ProjectSettings updated = projectService.updateSetting(projectId, "test_key", Map.of("value", "new"), "New description");

        // Then
        assertThat(updated.getSettingValue()).isEqualTo(Map.of("value", "new"));
        assertThat(updated.getDescription()).isEqualTo("New description");
        assertThat(updated.getUpdatedBy()).isEqualTo(testUser);

        verify(projectSettingsRepository).save(any(ProjectSettings.class));
        verify(auditService).log(eq("PROJECT_SETTING_UPDATED"), eq("PROJECT_SETTING"), eq(projectId), eq("test_key"), any(Map.class));
    }

    @Test
    void updateSetting_WhenNoAuthenticatedUser_ShouldThrowException() {
        // Given
        when(currentUserService.getCurrentUser()).thenReturn(Optional.empty());
        when(projectSettingsRepository.findByProjectIdAndSettingKey(projectId, "test_key"))
                .thenReturn(Optional.of(new ProjectSettings()));

        // When & Then
        assertThatThrownBy(() -> projectService.updateSetting(projectId, "test_key", Map.of(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No authenticated user");
    }

    @Test
    void deleteSetting_ShouldDelete() {
        // Given
        ProjectSettings existing = new ProjectSettings();
        existing.setSettingKey("test_key");
        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(testUserPrincipal));
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(projectSettingsRepository.findByProjectIdAndSettingKey(projectId, "test_key"))
                .thenReturn(Optional.of(existing));
        doNothing().when(projectSettingsRepository).deleteByProjectIdAndSettingKey(projectId, "test_key");

        // When
        projectService.deleteSetting(projectId, "test_key");

        // Then
        verify(projectSettingsRepository).deleteByProjectIdAndSettingKey(projectId, "test_key");
        verify(auditService).log(eq("PROJECT_SETTING_DELETED"), eq("PROJECT_SETTING"), eq(projectId), eq("test_key"), any(Map.class));
    }

    @Test
    void deleteSetting_WhenNotFound_ShouldDoNothing() {
        // Given
        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(testUserPrincipal));
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(projectSettingsRepository.findByProjectIdAndSettingKey(projectId, "nonexistent"))
                .thenReturn(Optional.empty());

        // When
        projectService.deleteSetting(projectId, "nonexistent");

        // Then
        verify(projectSettingsRepository, never()).delete(any());
        verify(auditService, never()).log(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void initializeDefaultSettings_ShouldCreateDefaultSettings() {
        // Given
        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(testUserPrincipal));
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(projectRepository.getReferenceById(projectId)).thenReturn(testProject);
        when(projectSettingsRepository.findByProjectIdAndSettingKey(any(UUID.class), anyString()))
                .thenReturn(Optional.empty());
        when(projectSettingsRepository.save(any(ProjectSettings.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        projectService.initializeDefaultSettings(projectId, testUser);

        // Then
        // Should create default settings for: allow_anonymous_submissions, require_review_before_approval, default_form_status, max_submission_size_mb, timezone, supported_languages
        verify(projectSettingsRepository, times(6)).save(any(ProjectSettings.class));
    }

    @Test
    void getAllSettingsAsMap_ShouldReturnSettingsMap() {
        // Given
        ProjectSettings setting1 = new ProjectSettings();
        setting1.setSettingKey("key1");
        setting1.setSettingValue(Map.of("value", "val1"));

        ProjectSettings setting2 = new ProjectSettings();
        setting2.setSettingKey("key2");
        setting2.setSettingValue(Map.of("value", "val2"));

        when(projectSettingsRepository.findByProjectId(projectId)).thenReturn(List.of(setting1, setting2));

        // When
        Map<String, Object> result = projectService.getAllSettingsAsMap(projectId);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result.get("key1")).isEqualTo(Map.of("value", "val1"));
        assertThat(result.get("key2")).isEqualTo(Map.of("value", "val2"));
    }

    @Test
    void getSetting_ShouldReturnSetting() {
        // Given
        ProjectSettings setting = new ProjectSettings();
        setting.setSettingKey("test_key");
        setting.setSettingValue(Map.of("value", "test"));
        when(projectSettingsRepository.findByProjectIdAndSettingKey(projectId, "test_key"))
                .thenReturn(Optional.of(setting));

        // When
        Optional<ProjectSettings> result = projectService.getSetting(projectId, "test_key");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getSettingKey()).isEqualTo("test_key");
    }
}