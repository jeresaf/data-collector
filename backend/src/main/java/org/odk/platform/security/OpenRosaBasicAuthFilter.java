package org.odk.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Base64;

@Component
@RequiredArgsConstructor
@Slf4j
public class OpenRosaBasicAuthFilter extends OncePerRequestFilter {

    private final UserDetailsServiceImpl userDetailsService;
    private final PasswordEncoder passwordEncoder;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        // Only apply to OpenRosa endpoints (/v1/** and compatibility endpoints)
        String path = request.getRequestURI();
        if (!isOpenRosaEndpoint(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Check if already authenticated
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Basic ")) {
            // This filter only attempts Basic authentication. Authorization
            // remains the responsibility of the Spring Security filter chain.
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String base64Credentials = authHeader.substring("Basic ".length()).trim();
            byte[] credDecoded = Base64.getDecoder().decode(base64Credentials);
            String credentials = new String(credDecoded);
            String[] values = credentials.split(":", 2);

            if (values.length != 2) {
                filterChain.doFilter(request, response);
                return;
            }

            String username = values[0];
            String password = values[1];

            log.debug("Attempting OpenRosa Basic authentication for username: {}", username);

            UserDetails userDetails;
            try {
                userDetails = userDetailsService.loadAppUserByUsername(username);
            } catch (Exception e) {
                // Do not reveal whether a username exists.
                log.debug("OpenRosa Basic authentication failed for username: {}", username);
                filterChain.doFilter(request, response);
                return;
            }

            boolean matches;
            try {
                matches = passwordEncoder.matches(password, userDetails.getPassword());
            } catch (Exception e) {
                log.debug("OpenRosa password verification failed for username: {}", username);
                filterChain.doFilter(request, response);
                return;
            }

            if (!matches) {
                log.debug("OpenRosa Basic authentication rejected for username: {}", username);
                filterChain.doFilter(request, response);
                return;
            }

            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                    userDetails,
                    null,
                    userDetails.getAuthorities()
            );
            authToken.setDetails(buildWebAuthenticationDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);

        } catch (Exception e) {
            // Malformed Basic credentials are simply left unauthenticated.
            log.debug("Ignoring malformed OpenRosa Basic authentication header");
        }

        filterChain.doFilter(request, response);
    }

    private org.springframework.security.web.authentication.WebAuthenticationDetails buildWebAuthenticationDetails(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // Use the first IP in the X-Forwarded-For chain
            remoteAddress = xForwardedFor.split(",")[0].trim();
        }
        
        org.springframework.security.web.authentication.WebAuthenticationDetails details = 
                new org.springframework.security.web.authentication.WebAuthenticationDetails(request);
        // Use reflection to set the remote address since WebAuthenticationDetails doesn't have a setter
        try {
            java.lang.reflect.Field field = org.springframework.security.web.authentication.WebAuthenticationDetails.class.getDeclaredField("remoteAddress");
            field.setAccessible(true);
            field.set(details, remoteAddress);
        } catch (Exception e) {
            // If reflection fails, use the default details
        }
        return details;
    }

    /**
     * Check if the given path is an OpenRosa endpoint that should use Basic Auth.
     * This includes both /v1/** endpoints and compatibility endpoints (/formList, /submission, /forms/**).
     */
    private boolean isOpenRosaEndpoint(String path) {
        // Standard v1 endpoints
        if (path.startsWith("/v1/")) {
            return true;
        }
        
        // Compatibility endpoints (OpenRosa 1.0 compatibility aliases)
        if (path.equals("/formList") || path.equals("/submission")) {
            return true;
        }
        
        // Form download, manifest, and attachment endpoints
        if (path.startsWith("/forms/")) {
            return true;
        }
        
        return false;
    }
}
