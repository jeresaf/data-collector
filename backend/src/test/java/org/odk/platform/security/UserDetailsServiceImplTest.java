package org.odk.platform.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {

    @InjectMocks
    private UserDetailsServiceImpl userDetailsService;

    @Mock
    private org.odk.platform.repository.UserRepository userRepository;

    @Mock
    private org.odk.platform.repository.AppUserRepository appUserRepository;

    @Test
    void loadUserByUsername_WithValidWebUser_ReturnsUserDetails() {
        // Arrange
        org.odk.platform.domain.User user = org.odk.platform.domain.User.builder()
                .id(UUID.randomUUID())
                .username("testuser")
                .passwordHash("$2a$12$testhash")
                .email("test@example.com")
                .firstName("Test")
                .lastName("User")
                .isActive(true)
                .isVerified(true)
                .build();

        org.odk.platform.domain.Role role = org.odk.platform.domain.Role.builder()
                .id(UUID.randomUUID())
                .name("PROJECT_ADMIN")
                .build();

        org.odk.platform.domain.Permission permission = org.odk.platform.domain.Permission.builder()
                .id(UUID.randomUUID())
                .name("FORMS_READ")
                .build();

        role.setPermissions(java.util.Set.of(permission));
        user.setRoles(java.util.Set.of(role));

        when(userRepository.findByUsernameWithRolesAndPermissions("testuser"))
                .thenReturn(Optional.of(user));

        // Act
        UserDetails userDetails = userDetailsService.loadUserByUsername("testuser");

        // Assert
        assertNotNull(userDetails);
        assertEquals("testuser", userDetails.getUsername());
        assertEquals("$2a$12$testhash", userDetails.getPassword());
        assertTrue(userDetails.isEnabled());
        assertTrue(userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_PROJECT_ADMIN")));
        assertTrue(userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_FORMS_READ")));
    }

    @Test
    void loadUserByUsername_WithInactiveWebUser_ThrowsException() {
        // Arrange
        org.odk.platform.domain.User user = org.odk.platform.domain.User.builder()
                .id(UUID.randomUUID())
                .username("inactiveuser")
                .passwordHash("$2a$12$testhash")
                .email("inactive@example.com")
                .firstName("Inactive")
                .lastName("User")
                .isActive(false)
                .isVerified(true)
                .build();

        when(userRepository.findByUsernameWithRolesAndPermissions("inactiveuser"))
                .thenReturn(Optional.of(user));

        // Act & Assert
        assertThrows(UsernameNotFoundException.class, () -> 
                userDetailsService.loadUserByUsername("inactiveuser"));
    }

    @Test
    void loadUserByUsername_WithValidUnassignedAppUser_ReturnsUserDetails() {
        // Arrange
        org.odk.platform.domain.AppUser appUser = org.odk.platform.domain.AppUser.builder()
                .id(UUID.randomUUID())
                .username("appuser")
                .passwordHash("$2a$12$testhash")
                .fullName("App User")
                .isActive(true)
                .build();

        when(userRepository.findByUsernameWithRolesAndPermissions("appuser"))
                .thenReturn(Optional.empty());
        when(appUserRepository.findByUsername("appuser"))
                .thenReturn(Optional.of(appUser));

        // Act
        UserDetails userDetails = userDetailsService.loadUserByUsername("appuser");

        // Assert
        assertNotNull(userDetails);
        assertEquals("appuser", userDetails.getUsername());
        assertEquals("$2a$12$testhash", userDetails.getPassword());
        assertTrue(userDetails.isEnabled());
        assertTrue(userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_APP_USER")));
    }

    @Test
    void loadUserByUsername_WithInactiveAppUser_ThrowsException() {
        // Arrange
        org.odk.platform.domain.AppUser appUser = org.odk.platform.domain.AppUser.builder()
                .id(UUID.randomUUID())
                .username("inactiveappuser")
                .passwordHash("$2a$12$testhash")
                .fullName("Inactive App User")
                .isActive(false)
                .build();

        when(userRepository.findByUsernameWithRolesAndPermissions("inactiveappuser"))
                .thenReturn(Optional.empty());
        when(appUserRepository.findByUsername("inactiveappuser"))
                .thenReturn(Optional.of(appUser));

        // Act & Assert
        assertThrows(UsernameNotFoundException.class, () -> 
                userDetailsService.loadUserByUsername("inactiveappuser"));
    }

    @Test
    void loadUserByUsername_WithNonExistentUser_ThrowsException() {
        // Arrange
        when(userRepository.findByUsernameWithRolesAndPermissions("nonexistent"))
                .thenReturn(Optional.empty());
        when(appUserRepository.findByUsername("nonexistent"))
                .thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(UsernameNotFoundException.class, () -> 
                userDetailsService.loadUserByUsername("nonexistent"));
    }
}