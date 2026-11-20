package org.odk.platform.controller;

import lombok.RequiredArgsConstructor;
import org.odk.platform.service.TypedMappingRecommendationService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Web JWT-only reviewer recommendation API. A recommendation is NOT a
 * mapping approval or capability grant and cannot be used by Android intake.
 * The service rechecks current web identity, role, work and exact digests.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/record-work-items/{workId}/typed-review-recommendations")
@RequiredArgsConstructor
public class TypedMappingRecommendationController {
    private final TypedMappingRecommendationService service;

    @GetMapping("/context")
    public ResponseEntity<TypedMappingRecommendationService.RecommendationContext> context(
            @PathVariable UUID projectId, @PathVariable UUID workId,
            @RequestHeader(value = "Authorization", required = false)
            String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.context(projectId, workId, authorization));
    }

    @PostMapping
    public ResponseEntity<TypedMappingRecommendationService.RecommendationReceipt> record(
            @PathVariable UUID projectId, @PathVariable UUID workId,
            @RequestHeader(value = "Authorization", required = false)
            String authorization,
            @RequestBody TypedMappingRecommendationService.RecommendationRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(service.record(projectId, workId, authorization, request));
    }
}
