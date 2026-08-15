package org.odk.platform.controller;

import lombok.RequiredArgsConstructor;
import org.odk.platform.service.WebFormWorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * User-facing web-form workflow projection.
 *
 * Runtime mutation endpoints intentionally remain in WebFormController.
 */
@RestController
@RequestMapping("/api/web/workflow")
@RequiredArgsConstructor
public class WebFormWorkflowController {

    private final WebFormWorkflowService workflowService;

    @GetMapping("/forms")
    @PreAuthorize("isAuthenticated()")
    public List<WebFormWorkflowService.AvailableFormView> listAvailableForms() {
        return workflowService.listAvailableForms();
    }

    @GetMapping("/forms/{formId}")
    @PreAuthorize("isAuthenticated()")
    public WebFormWorkflowService.AvailableFormView getAvailableForm(
            @PathVariable UUID formId) {
        return workflowService.getAvailableForm(formId);
    }

    @GetMapping("/sessions")
    @PreAuthorize("isAuthenticated()")
    public List<WebFormWorkflowService.SessionSummaryView> listSessions(
            @RequestParam(defaultValue = "draft") String status) {
        return workflowService.listSessions(status);
    }

    @GetMapping("/sessions/{sessionId}/review")
    @PreAuthorize("isAuthenticated()")
    public WebFormWorkflowService.SessionReviewView reviewSession(
            @PathVariable UUID sessionId) {
        return workflowService.getReview(sessionId);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> accessDenied(
            AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", message(exception, "Access denied")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(
            IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", message(exception, "Invalid request")));
    }

    private static String message(RuntimeException exception, String fallback) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? fallback
                : exception.getMessage();
    }
}
