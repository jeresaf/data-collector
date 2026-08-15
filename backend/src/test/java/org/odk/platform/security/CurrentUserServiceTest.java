package org.odk.platform.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.domain.User;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.UserPrincipal;
import org.odk.platform.repository.AppUserRepository;
import org.odk.platform.repository.UserRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class CurrentUserServiceTest {

    @InjectMocks
    private CurrentUserService currentUserService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setAuthentication(Authentication authentication) {
        SecurityContext context = new SecurityContextImpl();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    @Test
    void getCurrentUser_WithWebUserAuthentication_ReturnsUser() {
        // Arrange
        UUID userId = UUID.randomUUID();
        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username("testuser")
                .password("password")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_PROJECT_ADMIN")))
                .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        setAuthentication(authentication);

        org.odk.platform.domain.User user = org.odk.platform.domain.User.builder()
                .id(userId)
                .username("testuser")
                .email("test@example.com")
                .firstName("Test")
                .lastName("User")
                .isActive(true)
                .isVerified(true)
                .build();
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        // Act
        Optional<UserPrincipal> result = currentUserService.getCurrentUser();

        // Assert
        assertTrue(result.isPresent());
        assertEquals(userId, result.get().getId());
        assertEquals("testuser", result.get().getUsername());
        verify(userRepository).findByUsername("testuser");
    }

    @Test
    void getCurrentUser_WithAppUserAuthentication_ReturnsAppUser() {
        // Arrange
        UUID appUserId = UUID.randomUUID();
        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username("appuser")
                .password("password")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_APP_USER")))
                .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        setAuthentication(authentication);

        org.odk.platform.domain.AppUser appUser = org.odk.platform.domain.AppUser.builder()
                .id(appUserId)
                .username("appuser")
                .fullName("App User")
                .isActive(true)
                .build();
        when(userRepository.findByUsername("appuser")).thenReturn(Optional.empty());
        when(appUserRepository.findByUsername("appuser")).thenReturn(Optional.of(appUser));

        // Act
        Optional<UserPrincipal> result = currentUserService.getCurrentUser();

        // Assert
        assertTrue(result.isPresent());
        assertEquals(appUserId, result.get().getId());
        assertEquals("appuser", result.get().getUsername());
        verify(userRepository).findByUsername("appuser");
        verify(appUserRepository).findByUsername("appuser");
    }

    @Test
    void getCurrentUser_WithNoAuthentication_ReturnsEmpty() {
        // Arrange - no authentication in context
        setAuthentication(null);

        // Act
        Optional<UserPrincipal> result = currentUserService.getCurrentUser();

        // Assert
        assertFalse(result.isPresent());
        verifyNoInteractions(userRepository, appUserRepository);
    }

    @Test
    void getCurrentUser_WithAnonymousAuthentication_ReturnsEmpty() {
        // Arrange
        Authentication authentication = new AnonymousAuthenticationToken(
                "anonymous", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        setAuthentication(authentication);

        // Act
        Optional<UserPrincipal> result = currentUserService.getCurrentUser();

        // Assert
        assertFalse(result.isPresent());
        verifyNoInteractions(userRepository, appUserRepository);
    }

    @Test
    void getCurrentUser_WithWebUserNotFound_ReturnsEmpty() {
        // Arrange
        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username("nonexistent")
                .password("password")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        setAuthentication(authentication);

        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());
        when(appUserRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        // Act
        Optional<UserPrincipal> result = currentUserService.getCurrentUser();

        // Assert
        assertFalse(result.isPresent());
        verify(userRepository).findByUsername("nonexistent");
        verify(appUserRepository).findByUsername("nonexistent");
    }

    @Test
    void getCurrentUserId_WithWebUser_ReturnsUserId() {
        // Arrange
        UUID userId = UUID.randomUUID();
        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username("testuser")
                .password("password")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        setAuthentication(authentication);

        org.odk.platform.domain.User user = org.odk.platform.domain.User.builder()
                .id(userId)
                .username("testuser")
                .build();
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        // Act
        Optional<UUID> result = currentUserService.getCurrentUserId();

        // Assert
        assertTrue(result.isPresent());
        assertEquals(userId, result.get());
    }

    @Test
    void getCurrentUserId_WithAppUser_ReturnsAppUserId() {
        // Arrange
        UUID appUserId = UUID.randomUUID();
        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username("appuser")
                .password("password")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_APP_USER")))
                .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        setAuthentication(authentication);

        org.odk.platform.domain.AppUser appUser = org.odk.platform.domain.AppUser.builder()
                .id(appUserId)
                .username("appuser")
                .build();
        when(userRepository.findByUsername("appuser")).thenReturn(Optional.empty());
        when(appUserRepository.findByUsername("appuser")).thenReturn(Optional.of(appUser));

        // Act
        Optional<UUID> result = currentUserService.getCurrentUserId();

        // Assert
        assertTrue(result.isPresent());
        assertEquals(appUserId, result.get());
    }

    @Test
    void getCurrentUserId_WithNoAuthentication_ReturnsEmpty() {
        // Arrange
        setAuthentication(null);

        // Act
        Optional<UUID> result = currentUserService.getCurrentUserId();

        // Assert
        assertFalse(result.isPresent());
    }

    @Test
    void isAuthenticated_WithValidAuthentication_ReturnsTrue() {
        // Arrange
        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username("testuser")
                .password("password")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        setAuthentication(authentication);

        // Act
        boolean result = currentUserService.isAuthenticated();

        // Assert
        assertTrue(result);
    }

    @Test
    void isAuthenticated_WithNoAuthentication_ReturnsFalse() {
        // Arrange
        setAuthentication(null);

        // Act
        boolean result = currentUserService.isAuthenticated();

        // Assert
        assertFalse(result);
    }

    @Test
    void isAuthenticated_WithAnonymousAuthentication_ReturnsFalse() {
        // Arrange
        Authentication authentication = new AnonymousAuthenticationToken(
                "anonymous", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        setAuthentication(authentication);

        // Act
        boolean result = currentUserService.isAuthenticated();

        // Assert
        assertFalse(result);
    }

    @Test
    void isAuthenticated_WithUnauthenticatedToken_ReturnsFalse() {
        // Arrange
        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username("testuser")
                .password("password")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        authentication.setAuthenticated(false); // Not authenticated
        setAuthentication(authentication);

        // Act
        boolean result = currentUserService.isAuthenticated();

        // Assert
        assertFalse(result);
    }

    @Test
    void getCurrentUser_WithAppUserNotFound_ReturnsEmpty() {
        // Arrange
        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username("unknownappuser")
                .password("password")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_APP_USER")))
                .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        setAuthentication(authentication);

        when(userRepository.findByUsername("unknownappuser")).thenReturn(Optional.empty());
        when(appUserRepository.findByUsername("unknownappuser")).thenReturn(Optional.empty());

        // Act
        Optional<UserPrincipal> result = currentUserService.getCurrentUser();

        // Assert
        assertFalse(result.isPresent());
    }
}
