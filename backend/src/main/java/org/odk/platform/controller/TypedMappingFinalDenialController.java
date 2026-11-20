package org.odk.platform.controller;

import lombok.RequiredArgsConstructor;
import org.odk.platform.service.TypedMappingFinalDenialService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Read-only final-authorizer eligibility + explicit DENIAL. No APPROVE,
 * COPY, TRANSFER, SIGN, GRANT, edit or upload route is exposed.
 */
@RestController
@RequestMapping(
    "/api/projects/{projectId}/record-work-items/{workId}/typed-review-final-denials")
@RequiredArgsConstructor
public class TypedMappingFinalDenialController {
    private final TypedMappingFinalDenialService service;

    @GetMapping("/{recommendationId}/eligibility")
    public ResponseEntity<TypedMappingFinalDenialService.DenialEligibility> eligible(
            @PathVariable UUID projectId, @PathVariable UUID workId,
            @PathVariable UUID recommendationId,
            @RequestHeader(value = "Authorization", required = false)
            String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.eligibility(
                projectId, workId, recommendationId, authorization));
    }

    @PostMapping("/{recommendationId}")
    public ResponseEntity<TypedMappingFinalDenialService.DenialReceipt> deny(
            @PathVariable UUID projectId, @PathVariable UUID workId,
            @PathVariable UUID recommendationId,
            @RequestHeader(value = "Authorization", required = false)
            String authorization,
            @RequestBody TypedMappingFinalDenialService.DenialRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
            .body(service.deny(
                projectId, workId, recommendationId, authorization, request));
    }
}
