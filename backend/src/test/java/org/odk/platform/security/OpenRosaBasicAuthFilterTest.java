package org.odk.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenRosaBasicAuthFilterTest {

    @InjectMocks
    private OpenRosaBasicAuthFilter openRosaBasicAuthFilter;

    @Mock
    private UserDetailsServiceImpl userDetailsService;

    @Mock
    private PasswordEncoder passwordEncoder;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilterInternal_WithNonV1Path_ContinuesFilterChain() throws ServletException, IOException {
        // Arrange
        request.setRequestURI("/api/auth/login");

        // Act
        openRosaBasicAuthFilter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(userDetailsService, passwordEncoder);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilterInternal_WithV1PathButNoAuthHeader_ContinuesFilterChain() throws ServletException, IOException {
        // Arrange
        request.setRequestURI("/v1/projects/123/forms");

        // Act
        openRosaBasicAuthFilter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(userDetailsService, passwordEncoder);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilterInternal_WithV1PathAndNonBasicAuth_ContinuesFilterChain() throws ServletException, IOException {
        // Arrange
        request.setRequestURI("/v1/projects/123/forms");
        request.addHeader("Authorization", "Bearer some-token");

        // Act
        openRosaBasicAuthFilter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(userDetailsService, passwordEncoder);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilterInternal_WithValidBasicAuth_SetsAuthentication() throws ServletException, IOException {
        // Arrange
        request.setRequestURI("/v1/projects/123/forms");
        String credentials = "Basic " + java.util.Base64.getEncoder().encodeToString("appuser:password123".getBytes());
        request.addHeader("Authorization", credentials);

        MobileAppUserDetails userDetails = new MobileAppUserDetails(
                java.util.UUID.randomUUID(), "appuser", "$2a$10$encodedpassword", true);

        when(userDetailsService.loadAppUserByUsername("appuser")).thenReturn(userDetails);
        when(passwordEncoder.matches("password123", "$2a$10$encodedpassword")).thenReturn(true);

        // Act
        openRosaBasicAuthFilter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        verify(userDetailsService).loadAppUserByUsername("appuser");
        verify(passwordEncoder).matches("password123", "$2a$10$encodedpassword");

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("appuser", auth.getName());
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_APP_USER")));
    }

    @Test
    void doFilterInternal_WithInvalidPassword_DoesNotSetAuthentication() throws ServletException, IOException {
        // Arrange
        request.setRequestURI("/v1/projects/123/forms");
        String credentials = "Basic " + java.util.Base64.getEncoder().encodeToString("appuser:wrongpassword".getBytes());
        request.addHeader("Authorization", credentials);

        MobileAppUserDetails userDetails = new MobileAppUserDetails(
                java.util.UUID.randomUUID(), "appuser", "$2a$10$encodedpassword", true);

        when(userDetailsService.loadAppUserByUsername("appuser")).thenReturn(userDetails);
        when(passwordEncoder.matches("wrongpassword", "$2a$10$encodedpassword")).thenReturn(false);

        // Act
        openRosaBasicAuthFilter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        verify(userDetailsService).loadAppUserByUsername("appuser");
        verify(passwordEncoder).matches("wrongpassword", "$2a$10$encodedpassword");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilterInternal_WithNonExistentUser_DoesNotSetAuthentication() throws ServletException, IOException {
        // Arrange
        request.setRequestURI("/v1/projects/123/forms");
        String credentials = "Basic " + java.util.Base64.getEncoder().encodeToString("nonexistent:password123".getBytes());
        request.addHeader("Authorization", credentials);

        when(userDetailsService.loadAppUserByUsername("nonexistent"))
                .thenThrow(new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found"));

        // Act
        openRosaBasicAuthFilter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        verify(userDetailsService).loadAppUserByUsername("nonexistent");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilterInternal_WithMalformedBasicAuth_ContinuesFilterChain() throws ServletException, IOException {
        // Arrange
        request.setRequestURI("/v1/projects/123/forms");
        request.addHeader("Authorization", "Basic notvalidbase64!");

        // Act
        openRosaBasicAuthFilter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(userDetailsService, passwordEncoder);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilterInternal_WithBasicAuthMissingColon_ContinuesFilterChain() throws ServletException, IOException {
        // Arrange
        request.setRequestURI("/v1/projects/123/forms");
        // Base64 of "usernameonly" (no colon)
        String credentials = "Basic " + java.util.Base64.getEncoder().encodeToString("usernameonly".getBytes());
        request.addHeader("Authorization", credentials);

        // Act
        openRosaBasicAuthFilter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(userDetailsService, passwordEncoder);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilterInternal_WhenAlreadyAuthenticated_ContinuesFilterChain() throws ServletException, IOException {
        // Arrange
        request.setRequestURI("/v1/projects/123/forms");
        String credentials = "Basic " + java.util.Base64.getEncoder().encodeToString("appuser:password123".getBytes());
        request.addHeader("Authorization", credentials);

        // Pre-authenticate
        UserDetails existingUser = User.builder()
                .username("existinguser")
                .password("password")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
        Authentication existingAuth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                existingUser, null, existingUser.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(existingAuth);

        // Act
        openRosaBasicAuthFilter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(userDetailsService, passwordEncoder);
        // Should keep existing authentication
        assertEquals("existinguser", SecurityContextHolder.getContext().getAuthentication().getName());
    }

    @Test
    void doFilterInternal_WithExceptionDuringProcessing_ContinuesFilterChain() throws ServletException, IOException {
        // Arrange
        request.setRequestURI("/v1/projects/123/forms");
        String credentials = "Basic " + java.util.Base64.getEncoder().encodeToString("appuser:password123".getBytes());
        request.addHeader("Authorization", credentials);

        when(userDetailsService.loadAppUserByUsername("appuser"))
                .thenThrow(new RuntimeException("Database error"));

        // Act
        openRosaBasicAuthFilter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilterInternal_SetsWebAuthenticationDetails() throws ServletException, IOException {
        // Arrange
        request.setRequestURI("/v1/projects/123/forms");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("User-Agent", "ODK Collect v2023.1.0");
        
        String credentials = "Basic " + java.util.Base64.getEncoder().encodeToString("appuser:password123".getBytes());
        request.addHeader("Authorization", credentials);

        MobileAppUserDetails userDetails = new MobileAppUserDetails(
                java.util.UUID.randomUUID(), "appuser", "$2a$10$encodedpassword", true);

        when(userDetailsService.loadAppUserByUsername("appuser")).thenReturn(userDetails);
        when(passwordEncoder.matches("password123", "$2a$10$encodedpassword")).thenReturn(true);

        // Act
        openRosaBasicAuthFilter.doFilterInternal(request, response, filterChain);

        // Assert
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth.getDetails());
        assertTrue(auth.getDetails() instanceof org.springframework.security.web.authentication.WebAuthenticationDetails);
        
        org.springframework.security.web.authentication.WebAuthenticationDetails details = 
                (org.springframework.security.web.authentication.WebAuthenticationDetails) auth.getDetails();
        assertEquals("10.0.0.1", details.getRemoteAddress());
    }

    @Test
    void doFilterInternal_WithXForwardedForHeader_UsesForwardedIp() throws ServletException, IOException {
        // Arrange
        request.setRequestURI("/v1/projects/123/forms");
        request.addHeader("X-Forwarded-For", "192.168.1.100, 10.0.0.1");
        request.setRemoteAddr("127.0.0.1");
        
        String credentials = "Basic " + java.util.Base64.getEncoder().encodeToString("appuser:password123".getBytes());
        request.addHeader("Authorization", credentials);

        MobileAppUserDetails userDetails = new MobileAppUserDetails(
                java.util.UUID.randomUUID(), "appuser", "$2a$10$encodedpassword", true);

        when(userDetailsService.loadAppUserByUsername("appuser")).thenReturn(userDetails);
        when(passwordEncoder.matches("password123", "$2a$10$encodedpassword")).thenReturn(true);

        // Act
        openRosaBasicAuthFilter.doFilterInternal(request, response, filterChain);

        // Assert
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        org.springframework.security.web.authentication.WebAuthenticationDetails details = 
                (org.springframework.security.web.authentication.WebAuthenticationDetails) auth.getDetails();
        // Should use the first IP from X-Forwarded-For
        assertEquals("192.168.1.100", details.getRemoteAddress());
    }
}