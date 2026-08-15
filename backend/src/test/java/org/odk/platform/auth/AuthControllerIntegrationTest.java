package org.odk.platform.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.odk.platform.auth.dto.LoginRequest;
import org.odk.platform.auth.dto.LoginResponse;
import org.odk.platform.auth.AuthController.RefreshTokenRequest;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void ensureAdminFixture() {
        UUID adminId = ensureAdminUser();
        assignSystemAdminRole(adminId);
    }

    private UUID ensureAdminUser() {
        List<UUID> existingIds = jdbcTemplate.query(
                "SELECT id FROM users WHERE username = ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                "admin");

        String encodedPassword = passwordEncoder.encode("admin123");

        if (!existingIds.isEmpty()) {
            UUID adminId = existingIds.get(0);
            jdbcTemplate.update(
                    """
                    UPDATE users
                    SET password_hash = ?,
                        email = ?,
                        first_name = ?,
                        last_name = ?,
                        is_active = true,
                        is_verified = true,
                        is_superuser = true,
                        failed_login_attempts = 0,
                        locked_until = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    encodedPassword,
                    "admin@odk-platform.local",
                    "System",
                    "Administrator",
                    adminId);
            return adminId;
        }

        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO users (
                    id,
                    username,
                    email,
                    password_hash,
                    first_name,
                    last_name,
                    is_active,
                    is_verified,
                    is_superuser,
                    failed_login_attempts,
                    created_at,
                    updated_at
                )
                VALUES (?, 'admin', 'admin@odk-platform.local', ?, 'System',
                        'Administrator', true, true, true, 0,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                adminId,
                encodedPassword);

        return adminId;
    }

    private void assignSystemAdminRole(UUID adminId) {
        UUID roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM roles WHERE name = 'SYSTEM_ADMIN'",
                UUID.class);

        Long assignmentCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM user_roles
                WHERE user_id = ? AND role_id = ?
                """,
                Long.class,
                adminId,
                roleId);

        if (assignmentCount == null || assignmentCount == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO user_roles (
                        id,
                        user_id,
                        role_id,
                        project_id,
                        assigned_by,
                        created_at,
                        updated_at
                    )
                    VALUES (?, ?, ?, NULL, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    UUID.randomUUID(),
                    adminId,
                    roleId,
                    adminId);
        }
    }

    @Test
    void login_WithValidCredentials_ReturnsTokens() throws Exception {
        // Arrange
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").exists())
                .andExpect(jsonPath("$.userInfo.username").value("admin"))
                .andExpect(jsonPath("$.userInfo.email").value("admin@odk-platform.local"));
    }

    @Test
    void login_WithInvalidCredentials_ReturnsUnauthorized() throws Exception {
        // Arrange
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("wrongpassword");

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_WithMissingCredentials_ReturnsBadRequest() throws Exception {
        // Arrange
        LoginRequest request = new LoginRequest();
        request.setUsername("");
        request.setPassword("");

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"SYSTEM_ADMIN"})
    void me_WithAuthenticatedUser_ReturnsUserInfo() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.email").value("admin@odk-platform.local"));
    }

    @Test
    void me_WithoutAuthentication_ReturnsUnauthorized() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"SYSTEM_ADMIN"})
    void logout_WithValidToken_ReturnsOk() throws Exception {
        // Act & Assert
        mockMvc.perform(post("/api/auth/logout")
                .header("Authorization", "Bearer dummy-token"))
                .andExpect(status().isOk());
    }

    @Test
    void refreshToken_WithValidRefreshToken_ReturnsNewTokens() throws Exception {
        // First login to get refresh token
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("admin");
        loginRequest.setPassword("admin123");

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = loginResult.getResponse().getContentAsString();
        LoginResponse loginResponse = objectMapper.readValue(responseBody, LoginResponse.class);
        String refreshToken = loginResponse.getRefreshToken();

        // Now use refresh token to get new tokens
        RefreshTokenRequest refreshRequest = new RefreshTokenRequest();
        refreshRequest.setRefreshToken(refreshToken);

        // Act & Assert
        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void refreshToken_WithInvalidRefreshToken_ReturnsUnauthorized() throws Exception {
        // Arrange
        RefreshTokenRequest refreshRequest = new RefreshTokenRequest();
        refreshRequest.setRefreshToken("invalid-token");

        // Act & Assert
        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isUnauthorized());
    }
}