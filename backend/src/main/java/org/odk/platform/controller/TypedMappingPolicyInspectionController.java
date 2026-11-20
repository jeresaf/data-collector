package org.odk.platform.controller;

import lombok.RequiredArgsConstructor;
import org.odk.platform.service.TypedMappingPolicyInspectionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * JWT web independent-signer advisory candidate inspection.
 * No POST/approve/issue/revoke/upload handler exists in this milestone.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/record-work-items/{workId}/typed-rule-candidates")
@RequiredArgsConstructor
public class TypedMappingPolicyInspectionController {
    private final TypedMappingPolicyInspectionService service;

    @GetMapping("/{recommendationId}")
    public ResponseEntity<TypedMappingPolicyInspectionService.CandidateInspection> inspect(
            @PathVariable UUID projectId, @PathVariable UUID workId,
            @PathVariable UUID recommendationId,
            @RequestHeader(value = "Authorization", required = false)
            String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.inspect(projectId, workId, recommendationId, authorization));
    }
}
