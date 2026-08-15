package org.odk.platform.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.AuditLog;
import org.odk.platform.domain.User;
import org.odk.platform.repository.AppUserRepository;
import org.odk.platform.repository.AuditLogRepository;
import org.odk.platform.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final String ACTOR_USERNAME = "actorUsername";
    private static final String ACTOR_USER_ID = "actorUserId";
    private static final String ACTOR_APP_USER_ID = "actorAppUserId";
    private static final String IP_ADDRESS = "ipAddress";
    private static final String USER_AGENT = "userAgent";

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final AppUserRepository appUserRepository;

    /**
     * Audit writes are intentionally synchronous.
     *
     * SecurityContextHolder and RequestContextHolder are thread-local. Running the
     * complete audit write behind @Async caused actor/request context to disappear
     * before AuditLog was built, leaving security-sensitive rows without an actor.
     * Audit events are small writes and correctness is more important than moving
     * them to a worker thread.
     */
    public void log(String action,
                    String resourceType,
                    UUID resourceId,
                    String resourceName,
                    Map<String, Object> details) {
        try {
            AuditLog auditLog = buildAuditLog(
                    action, resourceType, resourceId, resourceName, details, "SUCCESS", null);
            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("Failed to write audit log: {}", e.getMessage(), e);
        }
    }

    public void logError(String action,
                         String resourceType,
                         UUID resourceId,
                         String resourceName,
                         Map<String, Object> details,
                         String errorMessage) {
        try {
            AuditLog auditLog = buildAuditLog(
                    action, resourceType, resourceId, resourceName, details, "ERROR", errorMessage);
            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("Failed to write audit log: {}", e.getMessage(), e);
        }
    }

    private AuditLog buildAuditLog(String action,
                                   String resourceType,
                                   UUID resourceId,
                                   String resourceName,
                                   Map<String, Object> details,
                                   String status,
                                   String errorMessage) {
        AuditLog auditLog = new AuditLog();
        auditLog.setAction(action);
        auditLog.setResourceType(resourceType);
        auditLog.setResourceId(resourceId != null ? resourceId.toString() : null);
        auditLog.setResourceName(resourceName);
        auditLog.setStatus(status);
        auditLog.setErrorMessage(errorMessage);
        auditLog.setCreatedAt(Instant.now());

        Map<String, Object> safeDetails = details == null
                ? Map.of()
                : new LinkedHashMap<>(details);

        if (!safeDetails.isEmpty()) {
            try {
                auditLog.setNewValues(objectMapper.writeValueAsString(safeDetails));
            } catch (Exception e) {
                log.warn("Failed to serialize audit details: {}", e.getMessage());
            }
        }

        resolveActor(auditLog, safeDetails);
        resolveRequestContext(auditLog, safeDetails);

        return auditLog;
    }

    private void resolveActor(AuditLog auditLog, Map<String, Object> details) {
        // Authentication events happen before the controller installs the returned
        // Authentication into SecurityContextHolder. The listener therefore passes
        // the authenticated username/id explicitly in the audit details.
        UUID explicitUserId = parseUuid(details.get(ACTOR_USER_ID));
        if (explicitUserId != null) {
            Optional<User> user = userRepository.findById(explicitUserId);
            if (user.isPresent()) {
                auditLog.setUser(user.get());
                return;
            }
        }

        UUID explicitAppUserId = parseUuid(details.get(ACTOR_APP_USER_ID));
        if (explicitAppUserId != null) {
            Optional<AppUser> appUser = appUserRepository.findById(explicitAppUserId);
            if (appUser.isPresent()) {
                auditLog.setAppUser(appUser.get());
                return;
            }
        }

        String explicitUsername = stringValue(details.get(ACTOR_USERNAME));
        if (explicitUsername != null) {
            if (resolveActorByUsername(auditLog, explicitUsername)) {
                return;
            }
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return;
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof User user) {
            auditLog.setUser(user);
            return;
        }
        if (principal instanceof AppUser appUser) {
            auditLog.setAppUser(appUser);
            return;
        }
        if (principal instanceof UserDetails userDetails) {
            resolveActorByUsername(auditLog, userDetails.getUsername());
        }
    }

    private boolean resolveActorByUsername(AuditLog auditLog, String username) {
        Optional<User> webUser = userRepository.findByUsername(username);
        if (webUser.isPresent()) {
            auditLog.setUser(webUser.get());
            return true;
        }

        Optional<AppUser> appUser = appUserRepository.findByUsername(username);
        if (appUser.isPresent()) {
            auditLog.setAppUser(appUser.get());
            return true;
        }
        return false;
    }

    private void resolveRequestContext(AuditLog auditLog, Map<String, Object> details) {
        // AuthenticationEventListener already resolves X-Forwarded-For correctly.
        // Prefer the caller-supplied values so they survive any future execution
        // boundary and so proxy-aware client IPs are preserved.
        String explicitIpAddress = stringValue(details.get(IP_ADDRESS));
        String explicitUserAgent = stringValue(details.get(USER_AGENT));

        if (explicitIpAddress != null) {
            auditLog.setIpAddress(explicitIpAddress);
        }
        if (explicitUserAgent != null) {
            auditLog.setUserAgent(explicitUserAgent);
        }

        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return;
        }

        HttpServletRequest request = attributes.getRequest();
        if (auditLog.getIpAddress() == null) {
            auditLog.setIpAddress(clientIpAddress(request));
        }
        if (auditLog.getUserAgent() == null) {
            auditLog.setUserAgent(request.getHeader("User-Agent"));
        }
        if (auditLog.getRequestId() == null) {
            auditLog.setRequestId(firstNonBlank(
                    request.getHeader("X-Request-ID"),
                    request.getHeader("X-Correlation-ID")));
        }
        if (auditLog.getSessionId() == null && request.getSession(false) != null) {
            auditLog.setSessionId(request.getSession(false).getId());
        }
    }

    private String clientIpAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private UUID parseUuid(Object value) {
        String text = stringValue(value);
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    public Optional<AuditLog> findById(UUID id) {
        return auditLogRepository.findById(id);
    }
}
