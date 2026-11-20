package org.odk.platform.controller;

import lombok.RequiredArgsConstructor;
import org.odk.platform.service.TypedMappingReviewerEligibilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only web reviewer boundary. Explicit web Bearer authentication and
 * current project-role membership are enforced in the service; this is NOT
 * an approval endpoint, nor part of mobile OpenRosa /v1 submission authority.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/record-work-items")
@RequiredArgsConstructor
public class TypedMappingReviewerEligibilityController {
    private final TypedMappingReviewerEligibilityService service;

    @GetMapping("/{workId}/typed-review-eligibility")
    public TypedMappingReviewerEligibilityService.ReviewerEligibility inspect(
            @PathVariable UUID projectId, @PathVariable UUID workId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.inspect(projectId, workId, authorization);
    }
}
