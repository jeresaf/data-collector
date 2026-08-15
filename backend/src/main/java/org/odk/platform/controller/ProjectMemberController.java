package org.odk.platform.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.odk.platform.service.ProjectMemberService;
import org.odk.platform.service.ProjectService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class ProjectMemberController {

    private final ProjectMemberService projectMemberService;
    private final ProjectService projectService;

    @GetMapping("/members")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ProjectMemberService.ProjectMemberView>> listMembers(
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(projectMemberService.listMembers(projectId));
    }

    @GetMapping("/member-candidates")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYSTEM_ADMIN') or hasRole('PROJECT_ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<List<ProjectMemberService.UserCandidate>> searchCandidates(
            @PathVariable UUID projectId,
            @RequestParam(name = "q", required = false, defaultValue = "") String query) {
        return ResponseEntity.ok(projectMemberService.searchCandidates(projectId, query));
    }

    @GetMapping("/member-roles")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYSTEM_ADMIN') or hasRole('PROJECT_ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<List<ProjectMemberService.ProjectRoleOption>> listAssignableRoles(
            @PathVariable UUID projectId) {
        // Validate project existence through the same member read path before exposing management options.
        projectMemberService.listMembers(projectId);
        return ResponseEntity.ok(projectMemberService.listAssignableRoles());
    }

    @PostMapping("/members")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYSTEM_ADMIN') or hasRole('PROJECT_ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<ProjectMemberService.ProjectMemberView> addMember(
            @PathVariable UUID projectId,
            @Valid @RequestBody AddProjectMemberRequest request) {

        return ResponseEntity.ok(projectMemberService.addMember(
                projectId,
                request.getUserId(),
                request.getRoleId(),
                projectService.getCurrentUserId()
        ));
    }

    @DeleteMapping("/members/{memberId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYSTEM_ADMIN') or hasRole('PROJECT_ADMIN') or hasRole('PROJECT_MANAGER')")
    public ResponseEntity<Void> removeMember(
            @PathVariable UUID projectId,
            @PathVariable UUID memberId) {

        projectMemberService.removeMember(projectId, memberId);
        return ResponseEntity.ok().build();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddProjectMemberRequest {
        @NotNull
        private UUID userId;

        @NotNull
        private UUID roleId;
    }
}
