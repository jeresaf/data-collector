package org.odk.platform.security;

import lombok.RequiredArgsConstructor;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.User;
import org.odk.platform.repository.AppUserRepository;
import org.odk.platform.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;
    private final AppUserRepository appUserRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // First try to find in web users table with roles and permissions
        Optional<User> webUser = userRepository.findByUsernameWithRolesAndPermissions(username);
        if (webUser.isPresent()) {
            User user = webUser.get();
            if (!user.getIsActive()) {
                throw new UsernameNotFoundException("User account is disabled");
            }
            return buildUserDetails(user);
        }

        // Then try to find in app users table (for ODK Collect/KoboCollect).
        // Authentication must not depend on project/form assignment; assignment is
        // authorization state and is checked by the OpenRosa controllers.
        Optional<AppUser> appUser = appUserRepository.findByUsername(username);
        if (appUser.isPresent()) {
            AppUser user = appUser.get();
            if (!user.getIsActive()) {
                throw new UsernameNotFoundException("App user account is disabled");
            }
            return buildAppUserDetails(user);
        }

        throw new UsernameNotFoundException("User not found: " + username);
    }

    private UserDetails buildUserDetails(User user) {
        List<SimpleGrantedAuthority> authorities = user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(permission -> new SimpleGrantedAuthority("ROLE_" + permission.getName()))
                .collect(Collectors.toList());

        // Also add role authorities
        List<SimpleGrantedAuthority> roleAuthorities = user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName()))
                .collect(Collectors.toList());
        authorities.addAll(roleAuthorities);

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(authorities)
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .disabled(!user.getIsActive())
                .build();
    }

    private UserDetails buildAppUserDetails(AppUser user) {
        // App users get basic app user role
        List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                new SimpleGrantedAuthority("ROLE_APP_USER")
        );

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(authorities)
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .disabled(!user.getIsActive())
                .build();
    }
}