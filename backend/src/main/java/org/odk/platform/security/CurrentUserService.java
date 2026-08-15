package org.odk.platform.security;

import lombok.RequiredArgsConstructor;
import org.odk.platform.domain.User;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.UserPrincipal;
import org.odk.platform.repository.UserRepository;
import org.odk.platform.repository.AppUserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;
    private final AppUserRepository appUserRepository;

    public Optional<UserPrincipal> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof UserDetails userDetails) {
            String username = userDetails.getUsername();
            
            // First try to find web user
            Optional<User> webUser = userRepository.findByUsername(username);
            if (webUser.isPresent()) {
                return webUser.map(u -> (UserPrincipal) u);
            }
            
            // Then try to find app user
            Optional<AppUser> appUser = appUserRepository.findByUsername(username);
            if (appUser.isPresent()) {
                return appUser.map(u -> (UserPrincipal) u);
            }
        }
        return Optional.empty();
    }

    public Optional<UUID> getCurrentUserId() {
        return getCurrentUser().map(UserPrincipal::getId);
    }

    public Optional<User> getCurrentWebUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof UserDetails userDetails) {
            String username = userDetails.getUsername();
            return userRepository.findByUsername(username);
        }
        return Optional.empty();
    }

    public boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof UserDetails;
    }
}
