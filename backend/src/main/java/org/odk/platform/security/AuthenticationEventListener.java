package org.odk.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.User;
import org.odk.platform.repository.UserRepository;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthenticationEventListener {

    private final AuditService auditService;
    private final UserRepository userRepository;

    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        Authentication authentication = event.getAuthentication();
        String username = getUsername(authentication);

        Map<String, Object> details = securityContextDetails(username);

        Optional<User> userOpt = userRepository.findByUsername(username);
        UUID userId = userOpt.map(User::getId).orElse(null);
        String resourceName = userOpt.map(User::getUsername).orElse(username);
        if (userId != null) {
            details.put("actorUserId", userId.toString());
        }

        auditService.log("LOGIN_SUCCESS", "USER", userId, resourceName, details);
    }

    @EventListener
    public void onAuthenticationFailure(AuthenticationFailureBadCredentialsEvent event) {
        Authentication authentication = event.getAuthentication();
        String username = getUsername(authentication);

        Map<String, Object> details = securityContextDetails(username);
        details.put("failureReason", event.getException().getMessage());

        Optional<User> userOpt = userRepository.findByUsername(username);
        UUID userId = userOpt.map(User::getId).orElse(null);
        String resourceName = userOpt.map(User::getUsername).orElse(username);
        if (userId != null) {
            details.put("actorUserId", userId.toString());
        }

        auditService.logError(
                "LOGIN_FAILURE",
                "USER",
                userId,
                resourceName,
                details,
                event.getException().getMessage());
    }

    private Map<String, Object> securityContextDetails(String username) {
        Map<String, Object> details = new HashMap<>();
        // AuthenticationSuccessEvent is published before AuthController installs
        // the returned Authentication in SecurityContextHolder, so preserve the
        // authenticated identity explicitly for the audit service.
        details.put("actorUsername", username);
        details.put("ipAddress", getClientIpAddress());
        details.put("userAgent", getUserAgent());
        return details;
    }

    private String getUsername(Authentication authentication) {
        if (authentication.getPrincipal() instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        }
        return authentication.getPrincipal().toString();
    }

    private String getClientIpAddress() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }

        HttpServletRequest request = attributes.getRequest();
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String getUserAgent() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        String userAgent = attributes.getRequest().getHeader("User-Agent");
        return userAgent != null ? userAgent : "unknown";
    }
}
