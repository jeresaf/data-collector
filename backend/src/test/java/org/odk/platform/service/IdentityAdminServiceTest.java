package org.odk.platform.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.Role;
import org.odk.platform.domain.User;
import org.odk.platform.domain.UserRole;
import org.odk.platform.repository.AppUserRepository;
import org.odk.platform.repository.RoleRepository;
import org.odk.platform.repository.RefreshTokenRepository;
import org.odk.platform.repository.UserRepository;
import org.odk.platform.repository.UserRoleRepository;
import org.odk.platform.security.CurrentUserService;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdentityAdminServiceTest {

    @Mock UserRepository userRepository;
    @Mock AppUserRepository appUserRepository;
    @Mock RoleRepository roleRepository;
    @Mock UserRoleRepository userRoleRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuditService auditService;
    @Mock CurrentUserService currentUserService;

    IdentityAdminService service;
    User actor;

    @BeforeEach
    void setUp() {
        service = new IdentityAdminService(
                userRepository,
                appUserRepository,
                roleRepository,
                userRoleRepository,
                refreshTokenRepository,
                passwordEncoder,
                auditService,
                currentUserService);

        actor = User.builder()
                .id(UUID.randomUUID())
                .username("admin")
                .email("admin@example.org")
                .isActive(true)
                .isVerified(true)
                .build();
    }

    @Test
    void createUserHashesPasswordAssignsRoleAndAudits() {
        Role viewer = Role.builder()
                .id(UUID.randomUUID())
                .name("VIEWER")
                .displayName("Viewer")
                .isActive(true)
                .isSystem(true)
                .build();

        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(appUserRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(appUserRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secret123")).thenReturn("encoded");
        when(roleRepository.findByNameAndProjectIsNull("VIEWER")).thenReturn(Optional.of(viewer));
        when(currentUserService.getCurrentWebUser()).thenReturn(Optional.of(actor));
        when(userRoleRepository.findByUserIdAndProjectIsNull(any()))
                .thenReturn(
                        List.of(),
                        List.of(UserRole.builder().role(viewer).build()));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.randomUUID());
            return user;
        });

        var created = service.createUser(new IdentityAdminService.CreateWebUserCommand(
                "collector.manager",
                "manager@example.org",
                "secret123",
                "Collector",
                "Manager",
                null,
                Set.of("VIEWER")));

        assertThat(created.username()).isEqualTo("collector.manager");
        assertThat(created.roles()).containsExactly("VIEWER");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("encoded");
        assertThat(userCaptor.getValue().getIsVerified()).isTrue();

        verify(userRoleRepository).save(argThat(assignment ->
                assignment.getRole().getName().equals("VIEWER")
                        && assignment.getAssignedBy().getId().equals(actor.getId())));

        verify(auditService).log(
                eq(AuditActions.USER_CREATED),
                eq("USER"),
                any(UUID.class),
                eq("collector.manager"),
                anyMap());
    }

    @Test
    void cannotDeactivateLastActiveSystemAdministrator() {
        UUID targetId = UUID.randomUUID();
        User target = User.builder()
                .id(targetId)
                .username("only-admin")
                .email("only-admin@example.org")
                .isActive(true)
                .isVerified(true)
                .build();
        Role systemAdmin = Role.builder()
                .id(UUID.randomUUID())
                .name("SYSTEM_ADMIN")
                .isActive(true)
                .isSystem(true)
                .build();

        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(currentUserService.getCurrentWebUser()).thenReturn(Optional.of(actor));
        when(userRoleRepository.findByUserIdAndProjectIsNull(targetId))
                .thenReturn(List.of(UserRole.builder().user(target).role(systemAdmin).build()));
        when(userRoleRepository.countActiveUsersByGlobalRoleName("SYSTEM_ADMIN")).thenReturn(1L);

        assertThatThrownBy(() -> service.setUserActive(targetId, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one active SYSTEM_ADMIN");

        verify(userRepository, never()).save(target);
    }

    @Test
    void createAppUserAuditsWithoutExposingPassword() {
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(appUserRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(appUserRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(passwordEncoder.encode("mobilepass")).thenReturn("encoded-mobile");
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser appUser = invocation.getArgument(0);
            appUser.setId(UUID.randomUUID());
            return appUser;
        });

        var created = service.createAppUser(new IdentityAdminService.CreateAppUserCommand(
                "field-01",
                "field01@example.org",
                "mobilepass",
                "Field Collector One",
                "0700000000"));

        assertThat(created.username()).isEqualTo("field-01");

        ArgumentCaptor<java.util.Map<String, Object>> details = ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditService).log(
                eq(AuditActions.APP_USER_CREATED),
                eq("APP_USER"),
                any(UUID.class),
                eq("field-01"),
                details.capture());

        assertThat(details.getValue()).doesNotContainKeys("password", "passwordHash");
    }
}
