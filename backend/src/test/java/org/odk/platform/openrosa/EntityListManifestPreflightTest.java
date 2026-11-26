package org.odk.platform.openrosa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        byte[] modified = ("name,label,__version\n"
                + "d6b8a5e0-79d8-46e7-aab9-5bf1a57162ad,Household,2\n")
                .getBytes(StandardCharsets.UTF_8);
        String originalHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(VALID_MINIMUM));
        assertTrue(EntityListManifestPreflight.verifiedCandidate(
                declaration, modified, originalHash).isEmpty());

        byte[] malformed = "name,label,__version\nnot-uuid,Label,1\n"
                .getBytes(StandardCharsets.UTF_8);
        String malformedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(malformed));
        assertTrue(EntityListManifestPreflight.verifiedCandidate(
                declaration, malformed, malformedHash).isEmpty());
    }

    @Test
    void typedFilenameMustHaveOneUnambiguousNativeListIdentity() {
        for (String accepted : new String[] { "households.csv", "admin_units.csv", "phase-2.csv", "A1.csv" }) {
            assertTrue(EntityListManifestPreflight.validTypedFilename(accepted), accepted);
        }
        for (String rejected : new String[] {
                "households.CSV", "households.csv.backup.csv", "households.csv.csv",
                ".csv", "../households.csv", "with space.csv", "households.xml",
                "households.csv/../other.csv", "households.v2.csv"
        }) {
            assertFalse(EntityListManifestPreflight.validTypedFilename(rejected), rejected);
        }
        assertFalse(EntityListManifestPreflight.validTypedFilename(null));
        assertFalse(EntityListManifestPreflight.validTypedFilename("a".repeat(253) + ".csv"));
    }

    @Test
    void headerOnlySnapshotCannotQualifyForAutomaticEntityDeletion() throws Exception {
        byte[] headerOnly = "name,label,__version\n".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(headerOnly));
        for (String type : new String[] {"entityList", "approvalEntityList"}) {
            Map<String, Object> declared = Map.of("odkMediaType", type);
            assertTrue(EntityListManifestPreflight.validatedCandidate(declared, headerOnly).isEmpty());
            assertTrue(EntityListManifestPreflight.verifiedCandidate(declared, headerOnly, digest).isEmpty());
        }
    }

    @Test
    void malformedOrTruncatedSnapshotCannotQualifyEvenWithMatchingMd5() throws Exception {
        for (String csv : new String[] {
                "name,label,__version\n"
                        + "d6b8a5e0-79d8-46e7-aab9-5bf1a57162ad,One,1\n\n",
                "name,label,__version\n"
                        + "d6b8a5e0-79d8-46e7-aab9-5bf1a57162ad,One\n"
        }) {
            byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
            assertTrue(EntityListManifestPreflight.verifiedCandidate(
                    Map.of("odkMediaType", "entityList"), bytes, digest).isEmpty(), csv);
        }
    }

    @Test
    void verifiedTransitionReportsRemovedIdsWithoutPublishingDeletion() throws Exception {
        String firstId = "d6b8a5e0-79d8-46e7-aab9-5bf1a57162ad";
        String removedId = "34f2ce6b-34e3-49e0-9db8-5c194fca56da";
        byte[] previous = ("name,label,__version\n"
                + firstId + ",Existing,1\n"
                + removedId + ",Removed,1\n").getBytes(StandardCharsets.UTF_8);
        byte[] next = ("name,label,__version\n"
                + firstId + ",Updated,2\n").getBytes(StandardCharsets.UTF_8);
        String previousHash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(previous));
        String nextHash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(next));
        Map<String, Object> entityList = Map.of("odkMediaType", "entityList");
        assertEquals(Optional.of(java.util.Set.of(java.util.UUID.fromString(removedId))),
                EntityListManifestPreflight.removedIdsBetweenVerifiedSnapshots(
                        "households.csv", entityList, previous, previousHash,
                        "households.csv", entityList, next, nextHash));
        assertEquals(Optional.of(java.util.Set.of()),
                EntityListManifestPreflight.removedIdsBetweenVerifiedSnapshots(
                        "households.csv", entityList, next, nextHash,
                        "households.csv", entityList, next, nextHash));
    }

    @Test
    void removalComparisonFailsClosedForChangedBytesMissingHashAndDifferentType() throws Exception {
        byte[] first = VALID_MINIMUM;
        byte[] second = ("name,label,__version\n"
                + "34f2ce6b-34e3-49e0-9db8-5c194fca56da,Other,2\n")
                .getBytes(StandardCharsets.UTF_8);
        String firstHash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(first));
        String secondHash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(second));
        Map<String, Object> entity = Map.of("odkMediaType", "entityList");
        Map<String, Object> approval = Map.of("odkMediaType", "approvalEntityList");
        assertTrue(EntityListManifestPreflight.removedIdsBetweenVerifiedSnapshots(
                "households.csv", entity, first, firstHash,
                "households.csv", entity, second, firstHash).isEmpty());
        assertTrue(EntityListManifestPreflight.removedIdsBetweenVerifiedSnapshots(
                "households.csv", entity, first, firstHash,
                "households.csv", approval, second, secondHash).isEmpty());
        assertTrue(EntityListManifestPreflight.removedIdsBetweenVerifiedSnapshots(
                "households.csv", entity, first, null,
                "households.csv", entity, second, secondHash).isEmpty());
        assertTrue(EntityListManifestPreflight.removedIdsBetweenVerifiedSnapshots(
                "households.csv", entity, first, firstHash,
                "households.csv", entity, "name,label,__version\n".getBytes(StandardCharsets.UTF_8),
                secondHash).isEmpty());
    }

    @Test
    void removalComparisonRejectsDifferentListNamesAndVersionRollback() throws Exception {
        String id = "d6b8a5e0-79d8-46e7-aab9-5bf1a57162ad";
        byte[] previous = ("name,label,__version\n" + id + ",Current,4\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] rollback = ("name,label,__version\n" + id + ",Stale,3\n")
                .getBytes(StandardCharsets.UTF_8);
        String oldHash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(previous));
        String rollbackHash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(rollback));
        Map<String, Object> entity = Map.of("odkMediaType", "entityList");

        assertTrue(EntityListManifestPreflight.removedIdsBetweenVerifiedSnapshots(
                "households.csv", entity, previous, oldHash,
                "villages.csv", entity, rollback, rollbackHash).isEmpty());
        assertTrue(EntityListManifestPreflight.removedIdsBetweenVerifiedSnapshots(
                "households.csv.backup.csv", entity, previous, oldHash,
                "households.csv.backup.csv", entity, rollback, rollbackHash).isEmpty());
        assertTrue(EntityListManifestPreflight.removedIdsBetweenVerifiedSnapshots(
                "households.csv", entity, previous, oldHash,
                "households.csv", entity, rollback, rollbackHash).isEmpty());
    }

    @Test
    void declaredRowCountMustMatchValidatedDataRecords() throws Exception {
        byte[] bytes = VALID_MINIMUM;
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        Map<String, Object> metadata = Map.of("odkMediaType", "entityList");
        assertEquals(Optional.of("entityList"),
                EntityListManifestPreflight.verifiedCandidate(metadata, bytes, digest, 1L));
        assertEquals(Optional.of("entityList"),
                EntityListManifestPreflight.verifiedCandidate(metadata, bytes, digest, null));
        for (long badCount : new long[] {0L, 2L, -1L, Long.MAX_VALUE}) {
            assertTrue(EntityListManifestPreflight.verifiedCandidate(
                    metadata, bytes, digest, badCount).isEmpty(),
                    "Must deny mismatched rowCount=" + badCount);
        }
    }

}
