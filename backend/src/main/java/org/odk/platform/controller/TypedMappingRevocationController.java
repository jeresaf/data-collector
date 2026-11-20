package org.odk.platform.controller;

import lombok.RequiredArgsConstructor;
import org.odk.platform.service.TypedMappingRevocationService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * JWT web Project Admin-only revocation WRITE endpoint.
 * No grant issuance, trust-key registration, activation, restore, historical
 * answer retrieval, first-save authorization or submission handler is added.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/typed-mapping-revocations")
@RequiredArgsConstructor
public class TypedMappingRevocationController {
    private final TypedMappingRevocationService service;

    @PostMapping("/grants/{grantId}")
    public ResponseEntity<TypedMappingRevocationService.RevocationReceipt> grant(
            @PathVariable UUID projectId, @PathVariable UUID grantId,
            @RequestHeader(value = "Authorization", required = false)
            String authorization,
            @RequestBody TypedMappingRevocationService.GrantRevocationRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
            .body(service.revokeGrant(projectId, grantId, authorization, request));
    }

    @PostMapping("/keys/{keyId}")
    public ResponseEntity<TypedMappingRevocationService.RevocationReceipt> key(
            @PathVariable UUID projectId, @PathVariable String keyId,
            @RequestHeader(value = "Authorization", required = false)
            String authorization,
            @RequestBody TypedMappingRevocationService.KeyRevocationRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
            .body(service.revokeKey(projectId, keyId, authorization, request));
    }
}
