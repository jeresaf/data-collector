package org.odk.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.User;
import org.odk.platform.repository.UserRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthenticationEventListenerTest {

    @InjectMocks
    private AuthenticationEventListener authenticationEventListener;

    @Mock
    private AuditService auditService;

    @Mock
    private UserRepository userRepository;

    private HttpServletRequest mockRequest;
    private ServletRequestAttributes requestAttributes;

    @BeforeEach
    void setUp() {
        mockRequest = mock(HttpServletRequest.class);

        // Production checks X-Forwarded-For before falling back to getRemoteAddr().
        // Most tests exercise the fallback path, so explicitly model the header as
        // absent. lenient() is intentional because the no-request-context tests
        // reset RequestContextHolder before invoking the listener.
        lenient().when(mockRequest.getHeader("X-Forwarded-For")).thenReturn(null);

        requestAttributes = new ServletRequestAttributes(mockRequest);
        RequestContextHolder.setRequestAttributes(requestAttributes);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void onAuthenticationSuccess_WithWebUser_LogsAuditEvent() {
        // Arrange
        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username("testuser")
                .password("password")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_PROJECT_ADMIN")))
                .build();

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        
        WebAuthenticationDetails webDetails = new WebAuthenticationDetails(mockRequest);
        authentication.setDetails(webDetails);

        when(mockRequest.getRemoteAddr()).thenReturn("192.168.1.100");
        when(mockRequest.getHeader("User-Agent")).thenReturn("Mozilla/5.0");

        org.odk.platform.domain.User user = org.odk.platform.domain.User.builder()
                .id(UUID.randomUUID())
                .username("testuser")
                .email("test@example.com")
                .firstName("Test")
                .lastName("User")
                .build();
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        AuthenticationSuccessEvent event = new AuthenticationSuccessEvent(authentication);

        // Act
        authenticationEventListener.onAuthenticationSuccess(event);

        // Assert
        ArgumentCaptor<String> actionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> entityTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> entityIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Map> detailsCaptor = ArgumentCaptor.forClass(Map.class);

        verify(auditService).log(
                actionCaptor.capture(),
                entityTypeCaptor.capture(),
                entityIdCaptor.capture(),
                anyString(),
                detailsCaptor.capture()
        );

        assertEquals("LOGIN_SUCCESS", actionCaptor.getValue());
        assertEquals("USER", entityTypeCaptor.getValue());
        assertEquals(user.getId(), entityIdCaptor.getValue());
        assertTrue(detailsCaptor.getValue().containsKey("ipAddress"));
        assertEquals("192.168.1.100", detailsCaptor.getValue().get("ipAddress"));
    }

    @Test
    void onAuthenticationSuccess_WithAppUser_LogsAuditEvent() {
        // Arrange
        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username("appuser")
                .password("password")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_APP_USER")))
                .build();

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        
        WebAuthenticationDetails webDetails = new WebAuthenticationDetails(mockRequest);
        authentication.setDetails(webDetails);

        when(mockRequest.getRemoteAddr()).thenReturn("10.0.0.50");
        when(mockRequest.getHeader("User-Agent")).thenReturn("ODK Collect v2023.1.0");

        org.odk.platform.domain.AppUser appUser = org.odk.platform.domain.AppUser.builder()
                .id(UUID.randomUUID())
                .username("appuser")
                .fullName("App User")
                .build();
        
        // AppUser is not in UserRepository, so it returns empty
        when(userRepository.findByUsername("appuser")).thenReturn(Optional.empty());

        AuthenticationSuccessEvent event = new AuthenticationSuccessEvent(authentication);

        // Act
        authenticationEventListener.onAuthenticationSuccess(event);

        // Assert
        ArgumentCaptor<String> actionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> entityTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> entityIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Map> detailsCaptor = ArgumentCaptor.forClass(Map.class);

        verify(auditService).log(
                actionCaptor.capture(),
                entityTypeCaptor.capture(),
                entityIdCaptor.capture(),
                anyString(),
                detailsCaptor.capture()
        );

        assertEquals("LOGIN_SUCCESS", actionCaptor.getValue());
        assertEquals("USER", entityTypeCaptor.getValue());
        assertNull(entityIdCaptor.getValue());
        assertTrue(detailsCaptor.getValue().containsKey("ipAddress"));
        assertEquals("10.0.0.50", detailsCaptor.getValue().get("ipAddress"));
    }

    @Test
    void onAuthenticationSuccess_WithUnknownUser_LogsAuditEventWithNullEntityId() {
        // Arrange
        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username("unknownuser")
                .password("password")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        
        WebAuthenticationDetails webDetails = new WebAuthenticationDetails(mockRequest);
        authentication.setDetails(webDetails);

        when(mockRequest.getRemoteAddr()).thenReturn("192.168.1.1");
        when(mockRequest.getHeader("User-Agent")).thenReturn("TestAgent");
        when(userRepository.findByUsername("unknownuser")).thenReturn(Optional.empty());

        AuthenticationSuccessEvent event = new AuthenticationSuccessEvent(authentication);

        // Act
        authenticationEventListener.onAuthenticationSuccess(event);

        // Assert
        ArgumentCaptor<String> actionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> entityTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> entityIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Map> detailsCaptor = ArgumentCaptor.forClass(Map.class);

        verify(auditService).log(
                actionCaptor.capture(),
                entityTypeCaptor.capture(),
                entityIdCaptor.capture(),
                anyString(),
                detailsCaptor.capture()
        );

        assertEquals("LOGIN_SUCCESS", actionCaptor.getValue());
        assertEquals("USER", entityTypeCaptor.getValue());
        assertNull(entityIdCaptor.getValue());
        assertTrue(detailsCaptor.getValue().containsKey("ipAddress"));
    }

    @Test
    void onAuthenticationSuccess_WithoutRequestContext_LogsAuditEvent() {
        // Arrange - no request context
        RequestContextHolder.resetRequestAttributes();

        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username("testuser")
                .password("password")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());

        AuthenticationSuccessEvent event = new AuthenticationSuccessEvent(authentication);

        // Act
        authenticationEventListener.onAuthenticationSuccess(event);

        // Assert
        ArgumentCaptor<String> actionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> entityTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> entityIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Map> detailsCaptor = ArgumentCaptor.forClass(Map.class);

        verify(auditService).log(
                actionCaptor.capture(),
                entityTypeCaptor.capture(),
                entityIdCaptor.capture(),
                anyString(),
                detailsCaptor.capture()
        );

        assertEquals("LOGIN_SUCCESS", actionCaptor.getValue());
        assertEquals("USER", entityTypeCaptor.getValue());
        assertNull(entityIdCaptor.getValue());
        assertTrue(detailsCaptor.getValue().containsKey("ipAddress"));
        assertEquals("unknown", detailsCaptor.getValue().get("ipAddress"));
    }

    @Test
    void onAuthenticationFailure_WithBadCredentials_LogsAuditEvent() {
        // Arrange
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "testuser", "wrongpassword");

        WebAuthenticationDetails webDetails = new WebAuthenticationDetails(mockRequest);
        authentication.setDetails(webDetails);

        when(mockRequest.getRemoteAddr()).thenReturn("192.168.1.200");
        when(mockRequest.getHeader("User-Agent")).thenReturn("curl/7.68.0");

        org.odk.platform.domain.User user = org.odk.platform.domain.User.builder()
                .id(UUID.randomUUID())
                .username("testuser")
                .email("test@example.com")
                .build();
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        AuthenticationFailureBadCredentialsEvent event = 
                new AuthenticationFailureBadCredentialsEvent(authentication, new org.springframework.security.authentication.BadCredentialsException("Bad credentials"));

        // Act
        authenticationEventListener.onAuthenticationFailure(event);

        // Assert
        ArgumentCaptor<String> actionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> entityTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> entityIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Map> detailsCaptor = ArgumentCaptor.forClass(Map.class);

        verify(auditService).logError(
                actionCaptor.capture(),
                entityTypeCaptor.capture(),
                entityIdCaptor.capture(),
                anyString(),
                detailsCaptor.capture(),
                anyString()
        );

        assertEquals("LOGIN_FAILURE", actionCaptor.getValue());
        assertEquals("USER", entityTypeCaptor.getValue());
        assertEquals(user.getId(), entityIdCaptor.getValue());
        assertTrue(detailsCaptor.getValue().containsKey("ipAddress"));
        assertEquals("192.168.1.200", detailsCaptor.getValue().get("ipAddress"));
        assertTrue(detailsCaptor.getValue().containsKey("failureReason"));
        assertEquals("Bad credentials", detailsCaptor.getValue().get("failureReason"));
    }

    @Test
    void onAuthenticationFailure_WithUnknownUser_LogsAuditEventWithNullEntityId() {
        // Arrange
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "nonexistent", "wrongpassword");

        WebAuthenticationDetails webDetails = new WebAuthenticationDetails(mockRequest);
        authentication.setDetails(webDetails);

        when(mockRequest.getRemoteAddr()).thenReturn("10.0.0.1");
        when(mockRequest.getHeader("User-Agent")).thenReturn("TestAgent");
        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        AuthenticationFailureBadCredentialsEvent event = 
                new AuthenticationFailureBadCredentialsEvent(authentication, new org.springframework.security.authentication.BadCredentialsException("Bad credentials"));

        // Act
        authenticationEventListener.onAuthenticationFailure(event);

        // Assert
        ArgumentCaptor<String> actionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> entityTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> entityIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Map> detailsCaptor = ArgumentCaptor.forClass(Map.class);

        verify(auditService).logError(
                actionCaptor.capture(),
                entityTypeCaptor.capture(),
                entityIdCaptor.capture(),
                anyString(),
                detailsCaptor.capture(),
                anyString()
        );

        assertEquals("LOGIN_FAILURE", actionCaptor.getValue());
        assertEquals("USER", entityTypeCaptor.getValue());
        assertNull(entityIdCaptor.getValue());
        assertTrue(detailsCaptor.getValue().containsKey("ipAddress"));
    }

    @Test
    void onAuthenticationFailure_WithoutRequestContext_LogsAuditEvent() {
        // Arrange - no request context
        RequestContextHolder.resetRequestAttributes();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "testuser", "wrongpassword");

        AuthenticationFailureBadCredentialsEvent event = 
                new AuthenticationFailureBadCredentialsEvent(authentication, new org.springframework.security.authentication.BadCredentialsException("Bad credentials"));

        // Act
        authenticationEventListener.onAuthenticationFailure(event);

        // Assert
        ArgumentCaptor<String> actionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> entityTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> entityIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Map> detailsCaptor = ArgumentCaptor.forClass(Map.class);

        verify(auditService).logError(
                actionCaptor.capture(),
                entityTypeCaptor.capture(),
                entityIdCaptor.capture(),
                anyString(),
                detailsCaptor.capture(),
                anyString()
        );

        assertEquals("LOGIN_FAILURE", actionCaptor.getValue());
        assertEquals("USER", entityTypeCaptor.getValue());
        assertNull(entityIdCaptor.getValue());
        assertTrue(detailsCaptor.getValue().containsKey("ipAddress"));
        assertEquals("unknown", detailsCaptor.getValue().get("ipAddress"));
    }

    @Test
    void onAuthenticationSuccess_WithXForwardedForHeader_UsesForwardedIp() {
        // Arrange
        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username("testuser")
                .password("password")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        
        WebAuthenticationDetails webDetails = new WebAuthenticationDetails(mockRequest);
        authentication.setDetails(webDetails);

        when(mockRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.195, 70.41.3.18");
        when(mockRequest.getHeader("User-Agent")).thenReturn("TestAgent");

        org.odk.platform.domain.User user = org.odk.platform.domain.User.builder()
                .id(UUID.randomUUID())
                .username("testuser")
                .build();
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        AuthenticationSuccessEvent event = new AuthenticationSuccessEvent(authentication);

        // Act
        authenticationEventListener.onAuthenticationSuccess(event);

        // Assert
        ArgumentCaptor<Map> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(anyString(), anyString(), any(), anyString(), detailsCaptor.capture());

        // Should use the first IP from X-Forwarded-For
        assertEquals("203.0.113.195", detailsCaptor.getValue().get("ipAddress"));
    }

    @Test
    void onAuthenticationFailure_WithExceptionMessage_IncludesExceptionInDetails() {
        // Arrange
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "testuser", "wrongpassword");

        WebAuthenticationDetails webDetails = new WebAuthenticationDetails(mockRequest);
        authentication.setDetails(webDetails);

        when(mockRequest.getRemoteAddr()).thenReturn("192.168.1.1");
        when(mockRequest.getHeader("User-Agent")).thenReturn("TestAgent");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());

        AuthenticationFailureBadCredentialsEvent event = 
                new AuthenticationFailureBadCredentialsEvent(authentication, new org.springframework.security.authentication.BadCredentialsException("User not found"));

        // Act
        authenticationEventListener.onAuthenticationFailure(event);

        // Assert
        ArgumentCaptor<Map> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logError(anyString(), anyString(), any(), anyString(), detailsCaptor.capture(), anyString());

        assertEquals("User not found", detailsCaptor.getValue().get("failureReason"));
    }
}