package org.odk.platform.controller;

import lombok.RequiredArgsConstructor;
import org.odk.platform.service.FormLifecycleHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/forms/{formId}/lifecycle")
@RequiredArgsConstructor
public class FormLifecycleController {

    private final FormLifecycleHistoryService historyService;

    @GetMapping("/versions")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYSTEM_ADMIN') or hasRole('PROJECT_ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<List<FormLifecycleHistoryService.FormVersionView>> listVersions(
            @PathVariable UUID projectId,
            @PathVariable UUID formId) {
        return ResponseEntity.ok(historyService.listVersions(projectId, formId));
    }

    @GetMapping("/history")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SYSTEM_ADMIN') or hasRole('PROJECT_ADMIN') or hasRole('PROJECT_MANAGER') or hasRole('USER')")
    public ResponseEntity<List<FormLifecycleHistoryService.LifecycleHistoryView>> listHistory(
            @PathVariable UUID projectId,
            @PathVariable UUID formId) {
        return ResponseEntity.ok(historyService.listHistory(projectId, formId));
    }
}
