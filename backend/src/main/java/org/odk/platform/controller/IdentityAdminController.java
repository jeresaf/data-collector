package org.odk.platform.controller;

import lombok.RequiredArgsConstructor;
import org.odk.platform.service.IdentityAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/identity")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class IdentityAdminController {

    private final IdentityAdminService identityAdminService;

    @GetMapping("/users")
    public List<IdentityAdminService.WebUserView> listUsers() {
        return identityAdminService.listUsers();
    }

    @PostMapping("/users")
    public ResponseEntity<IdentityAdminService.WebUserView> createUser(
            @RequestBody IdentityAdminService.CreateWebUserCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(identityAdminService.createUser(command));
    }

    @PutMapping("/users/{userId}")
    public IdentityAdminService.WebUserView updateUser(
            @PathVariable UUID userId,
            @RequestBody IdentityAdminService.UpdateWebUserCommand command) {
        return identityAdminService.updateUser(userId, command);
    }

    @PutMapping("/users/{userId}/roles")
    public IdentityAdminService.WebUserView replaceRoles(
            @PathVariable UUID userId,
            @RequestBody IdentityAdminService.ReplaceRolesCommand command) {
        return identityAdminService.replaceGlobalRoles(userId, command);
    }

    @PatchMapping("/users/{userId}/status")
    public IdentityAdminService.WebUserView setUserStatus(
            @PathVariable UUID userId,
            @RequestBody IdentityAdminService.StatusCommand command) {
        if (command.active() == null) {
            throw new IllegalArgumentException("active is required");
        }
        return identityAdminService.setUserActive(userId, command.active());
    }

    @GetMapping("/roles")
    public List<IdentityAdminService.RoleView> listRoles() {
        return identityAdminService.listGlobalRoles();
    }

    @GetMapping("/app-users")
    public List<IdentityAdminService.AppUserView> listAppUsers() {
        return identityAdminService.listAppUsers();
    }

    @PostMapping("/app-users")
    public ResponseEntity<IdentityAdminService.AppUserView> createAppUser(
            @RequestBody IdentityAdminService.CreateAppUserCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(identityAdminService.createAppUser(command));
    }

    @PutMapping("/app-users/{appUserId}")
    public IdentityAdminService.AppUserView updateAppUser(
            @PathVariable UUID appUserId,
            @RequestBody IdentityAdminService.UpdateAppUserCommand command) {
        return identityAdminService.updateAppUser(appUserId, command);
    }

    @PatchMapping("/app-users/{appUserId}/status")
    public IdentityAdminService.AppUserView setAppUserStatus(
            @PathVariable UUID appUserId,
            @RequestBody IdentityAdminService.StatusCommand command) {
        if (command.active() == null) {
            throw new IllegalArgumentException("active is required");
        }
        return identityAdminService.setAppUserActive(appUserId, command.active());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", message(exception, "Invalid request")));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", message(exception, "Resource not found")));
    }

    private static String message(RuntimeException exception, String fallback) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? fallback
                : exception.getMessage();
    }
}
