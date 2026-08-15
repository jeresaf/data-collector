package org.odk.platform.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.odk.platform.auth.dto.LoginRequest;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthenticationManager authenticationManager;

    @MockBean
    private org.odk.platform.security.JwtService jwtService;

    @MockBean
    private org.springframework.security.core.userdetails.UserDetailsService userDetailsService;

    @MockBean
    private org.odk.platform.repository.UserRepository userRepository;

    @MockBean
    private org.odk.platform.repository.RefreshTokenRepository refreshTokenRepository;

    @MockBean
    private org.odk.platform.audit.AuditService auditService;

    /**
     * This is a controller MVC slice with MockMvc filters disabled.
     * Mock the unrelated OpenRosa filter so @WebMvcTest does not try to
     * construct its real PasswordEncoder-dependent security infrastructure.
     */
    @MockBean
    private org.odk.platform.security.OpenRosaBasicAuthFilter openRosaBasicAuthFilter;

    /**
     * OdkPlatformApplication enables JPA auditing globally. A @WebMvcTest slice
     * intentionally has no JPA entities/metamodel, so the auditing handler would
     * otherwise fail with "JPA metamodel must not be empty".
     *
     * Mock the mapping context instead of loading JPA into this controller slice.
     */
    @MockBean(name = "jpaMappingContext")
    private JpaMetamodelMappingContext jpaMappingContext;

    @Test
    void login_WithValidCredentials_ReturnsTokens() throws Exception {
        // Arrange
        LoginRequest request = new LoginRequest("admin", "admin123");
        
        UserDetails userDetails = User.builder()
                .username("admin")
                .password("admin123")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(jwtService.generateToken(any(UserDetails.class))).thenReturn("access-token");
        when(jwtService.generateRefreshToken(any(UserDetails.class))).thenReturn("refresh-token");
        when(jwtService.getJwtExpiration()).thenReturn(3600000L);
        
        org.odk.platform.domain.User user = org.odk.platform.domain.User.builder()
                .id(UUID.randomUUID())
                .username("admin")
                .email("admin@odk-platform.local")
                .firstName("System")
                .lastName("Administrator")
                .build();
        when(userRepository.findByUsername("admin")).thenReturn(java.util.Optional.of(user));

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600000))
                .andExpect(jsonPath("$.userInfo.username").value("admin"))
                .andExpect(jsonPath("$.userInfo.email").value("admin@odk-platform.local"));
    }

    @Test
    void login_WithInvalidCredentials_ReturnsUnauthorized() throws Exception {
        // Arrange
        LoginRequest request = new LoginRequest("admin", "wrongpassword");
        
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new org.springframework.security.authentication.BadCredentialsException("Bad credentials"));

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_WithMissingUsername_ReturnsBadRequest() throws Exception {
        // Arrange
        LoginRequest request = new LoginRequest("", "admin123");

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_WithMissingPassword_ReturnsBadRequest() throws Exception {
        // Arrange
        LoginRequest request = new LoginRequest("admin", "");

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
    @Test
    void logout_WithValidBearerToken_RevokesRefreshTokensAndAudits() throws Exception {
        UUID userId = UUID.randomUUID();
        org.odk.platform.domain.User user = org.odk.platform.domain.User.builder()
                .id(userId)
                .username("admin")
                .email("admin@odk-platform.local")
                .build();
        when(jwtService.extractUsername("access-token")).thenReturn("admin");
        when(userRepository.findByUsername("admin")).thenReturn(java.util.Optional.of(user));

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk());

        verify(refreshTokenRepository).deleteByUserUsername("admin");
        verify(auditService).log(eq("LOGOUT"), eq("USER"), eq(userId), eq("admin"), anyMap());
    }

}