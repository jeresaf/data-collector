package org.odk.platform.controller;

import lombok.RequiredArgsConstructor;
import org.odk.platform.service.TypedMappingOfflineCeremonyService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * A third independent web Project Admin may commit an out-of-band bundle hash.
 * This API NEVER registers a trusted key or approves use of a signed grant.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/typed-key-ceremony-commitments/{requestId}")
@RequiredArgsConstructor
public class TypedMappingOfflineCeremonyController {
    private final TypedMappingOfflineCeremonyService service;

    @GetMapping("/context")
    public ResponseEntity<TypedMappingOfflineCeremonyService.CeremonyContext> context(
            @PathVariable UUID projectId, @PathVariable UUID requestId,
            @RequestHeader(value = "Authorization", required = false)
            String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.context(projectId, requestId, authorization));
    }

    @PostMapping
    public ResponseEntity<TypedMappingOfflineCeremonyService.CeremonyPacket> commit(
            @PathVariable UUID projectId, @PathVariable UUID requestId,
            @RequestHeader(value = "Authorization", required = false)
            String authorization,
            @RequestBody TypedMappingOfflineCeremonyService.CommitRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(service.commit(projectId, requestId, authorization, request));
    }

    @GetMapping("/evidence-packet")
    public ResponseEntity<TypedMappingOfflineCeremonyService.CeremonyPacket> packet(
            @PathVariable UUID projectId, @PathVariable UUID requestId,
            @RequestHeader(value = "Authorization", required = false)
            String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.packet(projectId, requestId, authorization));
    }
}
