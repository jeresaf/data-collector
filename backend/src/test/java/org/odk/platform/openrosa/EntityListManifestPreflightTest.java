package org.odk.platform.openrosa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
    @Test
    void validatesCanonicalUniqueUuidAndPositiveVersionAcrossRows() {
        String valid = "name,label,__version,notes\n"
                + "d6b8a5e0-79d8-46e7-aab9-5bf1a57162ad,\"Household, north\",1,\"quoted \"\"value\"\"\ncontinued\"\n"
                + "0c622aa1-3c8a-4e76-bdbc-824f6945d325,South,2,ok\n";
        assertEquals(Optional.of("entityList"), EntityListManifestPreflight.validatedCandidate(
                Map.of("odkMediaType", "entityList"), valid.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsDuplicateEntityIdsAndInvalidVersions() {
        String id = "d6b8a5e0-79d8-46e7-aab9-5bf1a57162ad";
        for (String csv : new String[] {
                "name,label,__version\n" + id + ",A,1\n" + id + ",B,2\n",
                "name,label,__version\n" + id + ",A,0\n",
                "name,label,__version\n" + id + ",A,-1\n",
                "name,label,__version\n" + id + ",A,2147483648\n",
                "name,label,__version\nnot-a-uuid,A,1\n",
                "name,label,__version\n" + id + ",A,01\n"
        }) {
            assertTrue(EntityListManifestPreflight.validatedCandidate(
                    Map.of("odkMediaType", "entityList"), csv.getBytes(StandardCharsets.UTF_8)).isEmpty(),
                    csv);
        }
    }

    @Test
    void rejectsTruncatedRowsMalformedQuotesAndRepeatedBlankRecords() {
        String id = "d6b8a5e0-79d8-46e7-aab9-5bf1a57162ad";
        for (String csv : new String[] {
                "name,label,__version\n" + id + ",A\n",
                "name,label,__version\n" + id + ",A,1,EXTRA\n",
                "name,label,__version\n" + id + ",\"unfinished,1\n",
                "name,label,__version\n" + id + ",A\"bad,1\n",
                "name,label,__version\n" + id + ",A,1\n\n"
        }) {
            assertTrue(EntityListManifestPreflight.validatedCandidate(
                    Map.of("odkMediaType", "entityList"), csv.getBytes(StandardCharsets.UTF_8)).isEmpty(),
                    csv);
        }
    }

    @Test
    void priorHeaderPreflightDoesNotImplyFullRowValidation() {
        byte[] malformedRow = "name,label,__version\nnot-a-uuid,Value,0\n"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(Optional.of("entityList"), EntityListManifestPreflight.declaredCandidate(
                Map.of("odkMediaType", "entityList"), malformedRow));
        assertTrue(EntityListManifestPreflight.validatedCandidate(
                Map.of("odkMediaType", "entityList"), malformedRow).isEmpty());
    }

    @Test
    void verifiedCandidateRequiresMd5MatchingExactBytes() throws Exception {
        Map<String, Object> declaration = Map.of("odkMediaType", "entityList");
        String actualHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(VALID_MINIMUM));

        assertEquals(Optional.of("entityList"),
                EntityListManifestPreflight.verifiedCandidate(declaration, VALID_MINIMUM, actualHash));
        assertEquals(Optional.of("entityList"),
                EntityListManifestPreflight.verifiedCandidate(declaration, VALID_MINIMUM,
                        "md5:" + actualHash.toUpperCase(java.util.Locale.ROOT)));

        assertTrue(EntityListManifestPreflight.verifiedCandidate(
                declaration, VALID_MINIMUM, "md5:00000000000000000000000000000000").isEmpty());
        assertTrue(EntityListManifestPreflight.verifiedCandidate(
                declaration, VALID_MINIMUM, "sha256:" + actualHash).isEmpty());
        assertTrue(EntityListManifestPreflight.verifiedCandidate(
                declaration, VALID_MINIMUM, null).isEmpty());
        assertTrue(EntityListManifestPreflight.verifiedCandidate(
                Map.of(), VALID_MINIMUM, actualHash).isEmpty());
    }

    @Test
    void verifiedCandidateRejectsChangedOrInvalidRecordsRegardlessOfMatchingDigest() throws Exception {
        Map<String, Object> declaration = Map.of("odkMediaType", "entityList");
        byte[] modified = ("name,label,__version\\n"
                + "d6b8a5e0-79d8-46e7-aab9-5bf1a57162ad,Household,2\\n")
                .getBytes(StandardCharsets.UTF_8);
        String originalHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(VALID_MINIMUM));
        assertTrue(EntityListManifestPreflight.verifiedCandidate(
                declaration, modified, originalHash).isEmpty());

        byte[] malformed = "name,label,__version\\nnot-uuid,Label,1\\n"
                .getBytes(StandardCharsets.UTF_8);
        String malformedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(malformed));
        assertTrue(EntityListManifestPreflight.verifiedCandidate(
                declaration, malformed, malformedHash).isEmpty());
    }

}
