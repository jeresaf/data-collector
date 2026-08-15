package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.odk.platform.audit.AuditActions;
import org.odk.platform.audit.AuditService;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.Role;
import org.odk.platform.domain.User;
import org.odk.platform.domain.UserRole;
import org.odk.platform.repository.AppUserRepository;
import org.odk.platform.repository.RoleRepository;
import org.odk.platform.repository.RefreshTokenRepository;
import org.odk.platform.repository.UserRepository;
import org.odk.platform.repository.UserRoleRepository;
import org.odk.platform.security.CurrentUserService;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class IdentityAdminService {

    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";
    private static final String DEFAULT_ROLE = "VIEWER";

    private final UserRepository userRepository;
    private final AppUserRepository appUserRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public List<WebUserView> listUsers() {
        return userRepository.findAll(Sort.by(Sort.Direction.ASC, "username"))
                .stream()
                .map(this::toWebUserView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AppUserView> listAppUsers() {
        return appUserRepository.findAll(Sort.by(Sort.Direction.ASC, "username"))
                .stream()
                .map(IdentityAdminService::toAppUserView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RoleView> listGlobalRoles() {
        return roleRepository.findByProjectIsNullAndIsActiveTrueOrderByDisplayNameAsc()
                .stream()
                .map(role -> new RoleView(
                        role.getId(),
                        role.getName(),
                        role.getDisplayName(),
                        role.getDescription(),
                        Boolean.TRUE.equals(role.getIsSystem()),
                        Boolean.TRUE.equals(role.getIsActive())))
                .toList();
    }

    public WebUserView createUser(CreateWebUserCommand command) {
        String username = required(command.username(), "Username");
        String email = required(command.email(), "Email").toLowerCase(Locale.ROOT);
        String password = validPassword(command.password());

        ensurePrincipalIdentityAvailable(username, email, null, false);

        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .firstName(trimToNull(command.firstName()))
                .lastName(trimToNull(command.lastName()))
                .phoneNumber(trimToNull(command.phoneNumber()))
                .isActive(true)
                .isVerified(true)
                .preferredLanguage("en")
                .timezone("UTC")
                .failedLoginAttempts(0)
                .metadata(new HashMap<>())
                .passwordChangedAt(Instant.now())
                .build();

        user = userRepository.save(user);

        LinkedHashSet<String> requestedRoles = normalizeRoles(command.roleNames());
        if (requestedRoles.isEmpty()) requestedRoles.add(DEFAULT_ROLE);
        replaceGlobalRolesInternal(user, requestedRoles, false);

        auditService.log(
                AuditActions.USER_CREATED,
                "USER",
                user.getId(),
                user.getUsername(),
                details(
                        "username", user.getUsername(),
                        "email", user.getEmail(),
                        "roles", new ArrayList<>(requestedRoles)));

        return toWebUserView(user);
    }

    public WebUserView updateUser(UUID userId, UpdateWebUserCommand command) {
        User user = getUser(userId);

        String username = required(command.username(), "Username");
        String email = required(command.email(), "Email").toLowerCase(Locale.ROOT);
        ensurePrincipalIdentityAvailable(username, email, userId, false);

        Map<String, Object> oldValues = details(
                "username", user.getUsername(),
                "email", user.getEmail(),
                "firstName", user.getFirstName(),
                "lastName", user.getLastName(),
                "phoneNumber", user.getPhoneNumber());

        user.setUsername(username);
        user.setEmail(email);
        user.setFirstName(trimToNull(command.firstName()));
        user.setLastName(trimToNull(command.lastName()));
        user.setPhoneNumber(trimToNull(command.phoneNumber()));

        boolean passwordChanged = command.password() != null && !command.password().isBlank();
        if (passwordChanged) {
            user.setPasswordHash(passwordEncoder.encode(validPassword(command.password())));
            user.setPasswordChangedAt(Instant.now());
        }

        user = userRepository.save(user);
        if (passwordChanged) {
            refreshTokenRepository.deleteByUserId(user.getId());
        }

        auditService.log(
                AuditActions.USER_UPDATED,
                "USER",
                user.getId(),
                user.getUsername(),
                details(
                        "old", oldValues,
                        "new", details(
                                "username", user.getUsername(),
                                "email", user.getEmail(),
                                "firstName", user.getFirstName(),
                                "lastName", user.getLastName(),
                                "phoneNumber", user.getPhoneNumber()),
                        "passwordChanged", passwordChanged));

        return toWebUserView(user);
    }

    public WebUserView setUserActive(UUID userId, boolean active) {
        User user = getUser(userId);
        User actor = currentActor();

        if (!active && actor.getId().equals(user.getId())) {
            throw new IllegalArgumentException("You cannot deactivate your own account");
        }

        if (!active && hasGlobalRole(user.getId(), SYSTEM_ADMIN)
                && userRoleRepository.countActiveUsersByGlobalRoleName(SYSTEM_ADMIN) <= 1) {
            throw new IllegalArgumentException("At least one active SYSTEM_ADMIN account is required");
        }

        boolean oldActive = Boolean.TRUE.equals(user.getIsActive());
        user.setIsActive(active);
        user = userRepository.save(user);
        if (!active) {
            refreshTokenRepository.deleteByUserId(user.getId());
        }

        auditService.log(
                AuditActions.USER_STATUS_CHANGED,
                "USER",
                user.getId(),
                user.getUsername(),
                details("oldActive", oldActive, "newActive", active));

        return toWebUserView(user);
    }

    public WebUserView replaceGlobalRoles(UUID userId, ReplaceRolesCommand command) {
        User user = getUser(userId);
        LinkedHashSet<String> requested = normalizeRoles(command.roleNames());
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("A web user must have at least one global role");
        }
        replaceGlobalRolesInternal(user, requested, true);
        return toWebUserView(user);
    }

    public AppUserView createAppUser(CreateAppUserCommand command) {
        String username = required(command.username(), "Username");
        String email = required(command.email(), "Email").toLowerCase(Locale.ROOT);
        String password = validPassword(command.password());

        ensurePrincipalIdentityAvailable(username, email, null, true);

        AppUser appUser = AppUser.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .fullName(trimToNull(command.fullName()))
                .phoneNumber(trimToNull(command.phoneNumber()))
                .isActive(true)
                .isVerified(true)
                .preferredLanguage("en")
                .timezone("UTC")
                .failedLoginAttempts(0)
                .metadata(new HashMap<>())
                .passwordChangedAt(Instant.now())
                .build();

        appUser = appUserRepository.save(appUser);

        auditService.log(
                AuditActions.APP_USER_CREATED,
                "APP_USER",
                appUser.getId(),
                appUser.getUsername(),
                details("username", appUser.getUsername(), "email", appUser.getEmail()));

        return toAppUserView(appUser);
    }

    public AppUserView updateAppUser(UUID appUserId, UpdateAppUserCommand command) {
        AppUser appUser = getAppUser(appUserId);

        String username = required(command.username(), "Username");
        String email = required(command.email(), "Email").toLowerCase(Locale.ROOT);
        ensurePrincipalIdentityAvailable(username, email, appUserId, true);

        Map<String, Object> oldValues = details(
                "username", appUser.getUsername(),
                "email", appUser.getEmail(),
                "fullName", appUser.getFullName(),
                "phoneNumber", appUser.getPhoneNumber());

        appUser.setUsername(username);
        appUser.setEmail(email);
        appUser.setFullName(trimToNull(command.fullName()));
        appUser.setPhoneNumber(trimToNull(command.phoneNumber()));

        boolean passwordChanged = command.password() != null && !command.password().isBlank();
        if (passwordChanged) {
            appUser.setPasswordHash(passwordEncoder.encode(validPassword(command.password())));
            appUser.setPasswordChangedAt(Instant.now());
        }

        appUser = appUserRepository.save(appUser);
        if (passwordChanged) {
            refreshTokenRepository.deleteByAppUserId(appUser.getId());
        }

        auditService.log(
                AuditActions.APP_USER_UPDATED,
                "APP_USER",
                appUser.getId(),
                appUser.getUsername(),
                details(
                        "old", oldValues,
                        "new", details(
                                "username", appUser.getUsername(),
                                "email", appUser.getEmail(),
                                "fullName", appUser.getFullName(),
                                "phoneNumber", appUser.getPhoneNumber()),
                        "passwordChanged", passwordChanged));

        return toAppUserView(appUser);
    }

    public AppUserView setAppUserActive(UUID appUserId, boolean active) {
        AppUser appUser = getAppUser(appUserId);
        boolean oldActive = Boolean.TRUE.equals(appUser.getIsActive());

        appUser.setIsActive(active);
        appUser = appUserRepository.save(appUser);
        if (!active) {
            refreshTokenRepository.deleteByAppUserId(appUser.getId());
        }

        auditService.log(
                AuditActions.APP_USER_STATUS_CHANGED,
                "APP_USER",
                appUser.getId(),
                appUser.getUsername(),
                details("oldActive", oldActive, "newActive", active));

        return toAppUserView(appUser);
    }

    private void replaceGlobalRolesInternal(
            User user,
            Set<String> requestedNames,
            boolean auditChange) {

        List<Role> requestedRoles = new ArrayList<>();
        for (String roleName : requestedNames) {
            Role role = roleRepository.findByNameAndProjectIsNull(roleName)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown global role: " + roleName));
            if (!Boolean.TRUE.equals(role.getIsActive())) {
                throw new IllegalArgumentException("Role is inactive: " + roleName);
            }
            requestedRoles.add(role);
        }

        List<UserRole> existing = userRoleRepository.findByUserIdAndProjectIsNull(user.getId());
        LinkedHashSet<String> oldNames = existing.stream()
                .map(ur -> ur.getRole().getName())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (oldNames.contains(SYSTEM_ADMIN)
                && !requestedNames.contains(SYSTEM_ADMIN)
                && Boolean.TRUE.equals(user.getIsActive())
                && userRoleRepository.countActiveUsersByGlobalRoleName(SYSTEM_ADMIN) <= 1) {
            throw new IllegalArgumentException("At least one active SYSTEM_ADMIN account is required");
        }

        Map<String, UserRole> existingByName = existing.stream()
                .collect(Collectors.toMap(
                        ur -> ur.getRole().getName(),
                        ur -> ur,
                        (left, right) -> left,
                        LinkedHashMap::new));

        List<UserRole> removed = existing.stream()
                .filter(ur -> !requestedNames.contains(ur.getRole().getName()))
                .toList();
        if (!removed.isEmpty()) {
            userRoleRepository.deleteAll(removed);
        }

        User actor = currentActor();
        for (Role role : requestedRoles) {
            if (!existingByName.containsKey(role.getName())) {
                userRoleRepository.save(UserRole.builder()
                        .user(user)
                        .role(role)
                        .project(null)
                        .assignedBy(actor)
                        .build());
            }
        }

        if (auditChange && !oldNames.equals(requestedNames)) {
            auditService.log(
                    AuditActions.USER_ROLE_CHANGED,
                    "USER",
                    user.getId(),
                    user.getUsername(),
                    details(
                            "oldRoles", new ArrayList<>(oldNames),
                            "newRoles", new ArrayList<>(requestedNames)));
        }
    }

    private WebUserView toWebUserView(User user) {
        List<String> roles = userRoleRepository.findByUserIdAndProjectIsNull(user.getId())
                .stream()
                .map(ur -> ur.getRole().getName())
                .distinct()
                .sorted()
                .toList();

        return new WebUserView(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getFullName(),
                user.getPhoneNumber(),
                Boolean.TRUE.equals(user.getIsActive()),
                Boolean.TRUE.equals(user.getIsVerified()),
                roles,
                user.getLastLoginAt(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }

    private static AppUserView toAppUserView(AppUser appUser) {
        return new AppUserView(
                appUser.getId(),
                appUser.getUsername(),
                appUser.getEmail(),
                appUser.getFullName(),
                appUser.getPhoneNumber(),
                Boolean.TRUE.equals(appUser.getIsActive()),
                Boolean.TRUE.equals(appUser.getIsVerified()),
                appUser.getLastLoginAt(),
                appUser.getCreatedAt(),
                appUser.getUpdatedAt());
    }

    private User getUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("Web user not found: " + userId));
    }

    private AppUser getAppUser(UUID appUserId) {
        return appUserRepository.findById(appUserId)
                .orElseThrow(() -> new NoSuchElementException("App user not found: " + appUserId));
    }

    private User currentActor() {
        return currentUserService.getCurrentWebUser()
                .orElseThrow(() -> new IllegalStateException(
                        "Identity administration requires an authenticated web user"));
    }

    private boolean hasGlobalRole(UUID userId, String roleName) {
        return userRoleRepository.findByUserIdAndProjectIsNull(userId)
                .stream()
                .anyMatch(ur -> roleName.equals(ur.getRole().getName()));
    }

    private void ensurePrincipalIdentityAvailable(
            String username,
            String email,
            UUID updatingId,
            boolean appUserTarget) {

        userRepository.findByUsername(username).ifPresent(existing -> {
            if (appUserTarget || !existing.getId().equals(updatingId)) {
                throw new IllegalArgumentException("Username is already in use");
            }
        });
        appUserRepository.findByUsername(username).ifPresent(existing -> {
            if (!appUserTarget || !existing.getId().equals(updatingId)) {
                throw new IllegalArgumentException("Username is already in use");
            }
        });

        userRepository.findByEmail(email).ifPresent(existing -> {
            if (appUserTarget || !existing.getId().equals(updatingId)) {
                throw new IllegalArgumentException("Email is already in use");
            }
        });
        appUserRepository.findByEmail(email).ifPresent(existing -> {
            if (!appUserTarget || !existing.getId().equals(updatingId)) {
                throw new IllegalArgumentException("Email is already in use");
            }
        });
    }

    private static String required(String value, String label) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return normalized;
    }

    private static String validPassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password is required");
        }
        if (password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        return password;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static LinkedHashSet<String> normalizeRoles(Collection<String> roles) {
        if (roles == null) return new LinkedHashSet<>();
        return roles.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Map<String, Object> details(Object... entries) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            Object value = entries[i + 1];
            if (value != null) result.put(String.valueOf(entries[i]), value);
        }
        return result;
    }

    public record CreateWebUserCommand(
            String username,
            String email,
            String password,
            String firstName,
            String lastName,
            String phoneNumber,
            Set<String> roleNames) {}

    public record UpdateWebUserCommand(
            String username,
            String email,
            String password,
            String firstName,
            String lastName,
            String phoneNumber) {}

    public record ReplaceRolesCommand(Set<String> roleNames) {}

    public record StatusCommand(Boolean active) {}

    public record CreateAppUserCommand(
            String username,
            String email,
            String password,
            String fullName,
            String phoneNumber) {}

    public record UpdateAppUserCommand(
            String username,
            String email,
            String password,
            String fullName,
            String phoneNumber) {}

    public record WebUserView(
            UUID id,
            String username,
            String email,
            String firstName,
            String lastName,
            String fullName,
            String phoneNumber,
            boolean active,
            boolean verified,
            List<String> roles,
            Instant lastLoginAt,
            Instant createdAt,
            Instant updatedAt) {}

    public record AppUserView(
            UUID id,
            String username,
            String email,
            String fullName,
            String phoneNumber,
            boolean active,
            boolean verified,
            Instant lastLoginAt,
            Instant createdAt,
            Instant updatedAt) {}

    public record RoleView(
            UUID id,
            String name,
            String displayName,
            String description,
            boolean system,
            boolean active) {}
}
