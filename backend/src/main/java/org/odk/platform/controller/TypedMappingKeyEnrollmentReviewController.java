package org.odk.platform.controller;

import lombok.RequiredArgsConstructor;
import org.odk.platform.service.TypedMappingKeyEnrollmentReviewService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Untrusted public Ed25519 key proposals, review-context and independent
 * non-authorizing review. No endpoint enrolls any key into active trust.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/typed-key-enrollment-requests")
@RequiredArgsConstructor
public class TypedMappingKeyEnrollmentReviewController {
    private final TypedMappingKeyEnrollmentReviewService service;

    @PostMapping
    public ResponseEntity<TypedMappingKeyEnrollmentReviewService.ProposalReceipt> propose(
            @PathVariable UUID projectId,
            @RequestHeader(value = "Authorization", required = false)
            String authorization,
            @RequestBody TypedMappingKeyEnrollmentReviewService.ProposalRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(service.propose(projectId, authorization, request));
    }

    @GetMapping("/{requestId}/review-context")
    public ResponseEntity<TypedMappingKeyEnrollmentReviewService.ReviewContext> context(
            @PathVariable UUID projectId, @PathVariable UUID requestId,
            @RequestHeader(value = "Authorization", required = false)
            String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.reviewContext(projectId, requestId, authorization));
    }

    @PostMapping("/{requestId}/reviews")
    public ResponseEntity<TypedMappingKeyEnrollmentReviewService.ReviewReceipt> review(
            @PathVariable UUID projectId, @PathVariable UUID requestId,
            @RequestHeader(value = "Authorization", required = false)
            String authorization,
            @RequestBody TypedMappingKeyEnrollmentReviewService.ReviewRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(service.review(projectId, requestId, authorization, request));
    }
}
