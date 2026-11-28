package org.odk.platform.controller;

import lombok.RequiredArgsConstructor;
import org.odk.platform.service.ExplicitMappingConsentEvidenceService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/record-work-items/{workId}/typed-native-candidates/{recommendationId}/consent-evidence")
@RequiredArgsConstructor
public class ExplicitMappingConsentEvidenceController {
    private final ExplicitMappingConsentEvidenceService service;

    @PostMapping
    public ResponseEntity<ExplicitMappingConsentEvidenceService.Evidence> attest(
            @PathVariable UUID projectId, @PathVariable UUID workId, @PathVariable UUID recommendationId,
            @RequestHeader(value="Authorization", required=false) String auth,
            @RequestBody ExplicitMappingConsentEvidenceService.Attestation request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.attest(projectId, workId, recommendationId, auth, request));
    }

    @PostMapping("/inspect")
    public ResponseEntity<ExplicitMappingConsentEvidenceService.Evidence> inspect(
            @PathVariable UUID projectId, @PathVariable UUID workId, @PathVariable UUID recommendationId,
            @RequestHeader(value="Authorization", required=false) String auth,
            @RequestBody ExplicitMappingConsentEvidenceService.Scope request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.inspect(projectId, workId, recommendationId, auth, request));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<ExplicitMappingConsentEvidenceService.Evidence> withdraw(
            @PathVariable UUID projectId, @PathVariable UUID workId, @PathVariable UUID recommendationId,
            @RequestHeader(value="Authorization", required=false) String auth,
            @RequestBody ExplicitMappingConsentEvidenceService.Withdrawal request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.withdraw(projectId, workId, recommendationId, auth, request));
    }
}
