package org.odk.platform.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.odk.platform.domain.User;
import org.odk.platform.repository.UserRepository;
import org.odk.platform.security.AuthenticationEventListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
class LoginAuditIntegrationTest {

    @Autowired AuthenticationEventListener listener;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private final String username = "audit-login-" + UUID.randomUUID();
    private UUID userId;

    @AfterEach
    void cleanUp() {
        RequestContextHolder.resetRequestAttributes();
        jdbc.update("DELETE FROM audit_log_details WHERE audit_log_id IN (SELECT id FROM audit_logs WHERE resource_name = ?)", username);
        jdbc.update("DELETE FROM audit_logs WHERE resource_name = ?", username);
        if (userId != null) jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void successfulLoginEventPersistsActorIpAndUserAgent() throws Exception {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash("hashed");
        user.setIsActive(true);
        user = userRepository.saveAndFlush(user);
        userId = user.getId();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.77");
        request.addHeader("X-Forwarded-For", "203.0.113.77, 192.0.2.77");
        request.addHeader("User-Agent", "LoginAuditIntegrationTest/1.0");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        UserDetails principal = org.springframework.security.core.userdetails.User.builder()
                .username(username)
                .password("irrelevant")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_PROJECT_ADMIN")))
                .build();
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(authentication));

        java.util.Map<String, Object> row = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            var rows = jdbc.queryForList(
                    "SELECT user_id, ip_address, user_agent FROM audit_logs WHERE action = 'LOGIN_SUCCESS' AND resource_name = ? ORDER BY created_at DESC",
                    username);
            if (!rows.isEmpty()) {
                row = rows.get(0);
                break;
            }
            Thread.sleep(20L);
        }

        assertNotNull(row);
        assertEquals(userId.toString(), String.valueOf(row.get("USER_ID")));
        assertEquals("203.0.113.77", row.get("IP_ADDRESS"));
        assertEquals("LoginAuditIntegrationTest/1.0", row.get("USER_AGENT"));
    }
}
