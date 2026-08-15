package org.odk.platform.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.auth.dto.LoginRequest;
import org.odk.platform.auth.dto.LoginResponse;
import org.odk.platform.domain.User;
import org.odk.platform.repository.RefreshTokenRepository;
import org.odk.platform.repository.UserRepository;
import org.odk.platform.security.JwtService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditService auditService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();

        String accessToken = jwtService.generateToken(userDetails);
        String refreshToken = jwtService.generateRefreshToken(userDetails);

        // Save refresh token
        User user = userRepository.findByUsername(userDetails.getUsername()).orElseThrow();
        org.odk.platform.domain.RefreshToken refreshTokenEntity = org.odk.platform.domain.RefreshToken.builder()
                .token(refreshToken)
                .user(user)
                .expiresAt(Instant.now().plusMillis(jwtService.getRefreshExpiration()))
                .build();
        refreshTokenRepository.save(refreshTokenEntity);

        // Build user info
        String[] roles = userDetails.getAuthorities().stream()
                .filter(a -> a.getAuthority().startsWith("ROLE_"))
                .map(a -> a.getAuthority().substring(5))
                .toArray(String[]::new);

        String[] permissions = userDetails.getAuthorities().stream()
                .filter(a -> !a.getAuthority().startsWith("ROLE_"))
                .map(a -> a.getAuthority())
                .toArray(String[]::new);

        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(
                user.getId().toString(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                roles,
                permissions
        );

        LoginResponse response = new LoginResponse(
                accessToken,
                refreshToken,
                "Bearer",
                jwtService.getJwtExpiration(),
                userInfo
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        String username = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                username = jwtService.extractUsername(token);
                if (username != null) {
                    refreshTokenRepository.deleteByUserUsername(username);
                }
            } catch (Exception e) {
                // Invalid/expired access tokens are still safe to log out locally.
            }
        }

        // Audit before clearing the security context so AuditService can resolve the
        // authenticated actor and request metadata when they are available.
        if (username != null && !username.isBlank()) {
            User user = userRepository.findByUsername(username).orElse(null);
            auditService.log(AuditActions.LOGOUT, "USER", user == null ? null : user.getId(), username,
                    Map.of("username", username));
        }

        SecurityContextHolder.clearContext();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public ResponseEntity<LoginResponse.UserInfo> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() instanceof String) {
            return ResponseEntity.status(401).build();
        }

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userRepository.findByUsernameWithRolesAndPermissions(userDetails.getUsername()).orElseThrow();

        String[] roles = user.getRoles().stream()
                .map(r -> r.getName())
                .toArray(String[]::new);

        String[] permissions = user.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(p -> p.getName())
                .distinct()
                .toArray(String[]::new);

        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(
                user.getId().toString(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                roles,
                permissions
        );

        return ResponseEntity.ok(userInfo);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refreshToken(@RequestBody RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();
        
        if (refreshToken == null) {
            return ResponseEntity.status(401).build();
        }

        String username;
        try {
            username = jwtService.extractUsername(refreshToken);
        } catch (io.jsonwebtoken.MalformedJwtException | io.jsonwebtoken.ExpiredJwtException | io.jsonwebtoken.UnsupportedJwtException | io.jsonwebtoken.security.SignatureException | IllegalArgumentException e) {
            return ResponseEntity.status(401).build();
        }

        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        
        if (!jwtService.isTokenValid(refreshToken, userDetails)) {
            return ResponseEntity.status(401).build();
        }
        
        // Verify refresh token exists in DB and not expired
        org.odk.platform.domain.RefreshToken storedToken = refreshTokenRepository.findByToken(refreshToken)
                .orElse(null);
        
        if (storedToken == null || storedToken.getExpiresAt().isBefore(Instant.now())) {
            if (storedToken != null) {
                refreshTokenRepository.delete(storedToken);
            }
            return ResponseEntity.status(401).build();
        }

        String newAccessToken = jwtService.generateToken(userDetails);
        String newRefreshToken = jwtService.generateRefreshToken(userDetails);

        // Rotate refresh token
        refreshTokenRepository.delete(storedToken);
        User user = userRepository.findByUsername(username).orElseThrow();
        org.odk.platform.domain.RefreshToken newRefreshTokenEntity = org.odk.platform.domain.RefreshToken.builder()
                .token(newRefreshToken)
                .user(user)
                .expiresAt(Instant.now().plusMillis(jwtService.getRefreshExpiration()))
                .build();
        refreshTokenRepository.save(newRefreshTokenEntity);

        String[] roles = userDetails.getAuthorities().stream()
                .filter(a -> a.getAuthority().startsWith("ROLE_"))
                .map(a -> a.getAuthority().substring(5))
                .toArray(String[]::new);

        String[] permissions = userDetails.getAuthorities().stream()
                .filter(a -> !a.getAuthority().startsWith("ROLE_"))
                .map(a -> a.getAuthority())
                .toArray(String[]::new);

        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(
                user.getId().toString(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                roles,
                permissions
        );

        LoginResponse response = new LoginResponse(
                newAccessToken,
                newRefreshToken,
                "Bearer",
                jwtService.getJwtExpiration(),
                userInfo
        );

        return ResponseEntity.ok(response);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RefreshTokenRequest {
        private String refreshToken;
    }
}