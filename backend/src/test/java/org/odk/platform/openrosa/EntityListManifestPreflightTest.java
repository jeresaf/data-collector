package org.odk.platform.openrosa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityListManifestPreflightTest {

    private static final byte[] VALID_MINIMUM = (
            "name,label,__version\n" +
            "d6b8a5e0-79d8-46e7-aab9-5bf1a57162ad,Household,1\n")
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void ordinaryCsvNeverBecomesTypedByExtensionOrHeaderAlone() {
        assertTrue(EntityListManifestPreflight.declaredCandidate(Map.of(), VALID_MINIMUM).isEmpty());
        assertTrue(EntityListManifestPreflight.declaredCandidate(
                Map.of("filename", "entities.csv"), VALID_MINIMUM).isEmpty());
    }

    @Test
    void explicitEntityAndApprovalDeclarationsProducePreflightCandidates() {
        assertEquals(Optional.of("entityList"), EntityListManifestPreflight.declaredCandidate(
                Map.of("odkMediaType", "entityList"), VALID_MINIMUM));
        assertEquals(Optional.of("approvalEntityList"), EntityListManifestPreflight.declaredCandidate(
                Map.of("odkMediaType", "approvalEntityList"), VALID_MINIMUM));
    }

    @Test
    void rejectsUnknownTypeAndMissingRequiredColumns() {
        assertTrue(EntityListManifestPreflight.declaredCandidate(
                Map.of("odkMediaType", "formMedia"), VALID_MINIMUM).isEmpty());
        assertTrue(EntityListManifestPreflight.declaredCandidate(
                Map.of("odkMediaType", "entityList"), "name,label\nrow,value\n".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(EntityListManifestPreflight.declaredCandidate(
                Map.of("odkMediaType", "entityList"), "name,__version\nrow,1\n".getBytes(StandardCharsets.UTF_8)).isEmpty());
    }

    @Test
    void rejectsAmbiguousDuplicateOrEmptyHeaderAndEmptyBody() {
        assertTrue(EntityListManifestPreflight.declaredCandidate(
                Map.of("odkMediaType", "entityList"),
                "name,label,label,__version\nx,y,z,1\n".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(EntityListManifestPreflight.declaredCandidate(
                Map.of("odkMediaType", "entityList"),
                "name,label,__version\n".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(EntityListManifestPreflight.declaredCandidate(
                Map.of("odkMediaType", "entityList"),
                "\"name\",label,__version\nx,y,1\n".getBytes(StandardCharsets.UTF_8)).isEmpty());
    }

    @Test
    void rejectsInvalidUtf8AndBinary() {
        assertTrue(EntityListManifestPreflight.declaredCandidate(
                Map.of("odkMediaType", "entityList"), new byte[] {(byte) 0xff, 0x00}).isEmpty());
    }
}
