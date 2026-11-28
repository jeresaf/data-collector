package org.odk.platform.controller;

import lombok.RequiredArgsConstructor;
import org.odk.platform.service.NativeTypedMappingInspectionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Independent web final-signer read-only JavaRosa schema verification.
 * This endpoint does not expose historical answers or provide grant tokens.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/record-work-items/{workId}/typed-native-candidates")
@RequiredArgsConstructor
public class NativeTypedMappingInspectionController {
    private final NativeTypedMappingInspectionService service;

    @org.springframework.web.bind.annotation.PostMapping("/{recommendationId}/explicit-inspection")
    public ResponseEntity<NativeTypedMappingInspectionService.ExplicitInspection> inspectExplicit(
            @PathVariable UUID projectId, @PathVariable UUID workId,
            @PathVariable UUID recommendationId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @org.springframework.web.bind.annotation.RequestBody
            org.odk.platform.service.ExplicitMappingManifest manifest) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.inspectExplicit(projectId, workId, recommendationId, authorization, manifest));
    }

    @GetMapping("/{recommendationId}")
    public ResponseEntity<NativeTypedMappingInspectionService.NativeInspection> inspect(
            @PathVariable UUID projectId, @PathVariable UUID workId,
            @PathVariable UUID recommendationId,
            @RequestHeader(value = "Authorization", required = false)
            String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.inspect(projectId, workId, recommendationId, authorization));
    }
}
