package org.odk.platform.openrosa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityListSnapshotEvidenceTest {
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID OTHER_PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID FORM = UUID.fromString("00000000-0000-0000-0000-000000000303");
    private static final UUID OTHER_FORM = UUID.fromString("00000000-0000-0000-0000-000000000304");
    private static final String ID_1 = "d6b8a5e0-79d8-46e7-aab9-5bf1a57162ad";
    private static final String ID_2 = "34f2ce6b-34e3-49e0-9db8-5c194fca56da";
    private static final byte[] OLD = ("name,label,__version\n"
            + ID_1 + ",Existing,1\n"
            + ID_2 + ",Removed,1\n").getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW = ("name,label,__version\n"
            + ID_1 + ",Updated,2\n").getBytes(StandardCharsets.UTF_8);

    private static String md5(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    }

    private static Optional<EntityListSnapshotEvidence.Snapshot> make(
            UUID project, UUID form, String filename, long revision,
            String type, byte[] content, Long rowCount) throws Exception {
        return EntityListSnapshotEvidence.fromVerifiedBytes(
                project, form, filename, revision,
                Map.of("odkMediaType", type), content, md5(content), rowCount);
    }

    @Test
    void recordsVerifiedProjectFormListRevisionAndTwoDigests() throws Exception {
        var old = make(PROJECT, FORM, "households.csv", 1L, "entityList", OLD, 2L);
        var next = make(PROJECT, FORM, "households.csv", 2L, "entityList", NEW, 1L);
        assertTrue(old.isPresent());
        assertTrue(next.isPresent());
        assertEquals(64, old.orElseThrow().sha256().length());
        assertEquals(32, old.orElseThrow().md5().length());
        assertEquals(Optional.of(Set.of(UUID.fromString(ID_2))),
                EntityListSnapshotEvidence.reviewMissingIds(old.orElseThrow(), OLD,
                        next.orElseThrow(), NEW));
    }

    @Test
    void rejectsCrossProjectCrossFormCrossListAndNonIncreasingRevisions() throws Exception {
        var old = make(PROJECT, FORM, "households.csv", 4L, "entityList", OLD, 2L).orElseThrow();
        var otherProject = make(OTHER_PROJECT, FORM, "households.csv", 5L,
                "entityList", NEW, 1L).orElseThrow();
        var otherForm = make(PROJECT, OTHER_FORM, "households.csv", 5L,
                "entityList", NEW, 1L).orElseThrow();
        var otherList = make(PROJECT, FORM, "villages.csv", 5L,
                "entityList", NEW, 1L).orElseThrow();
        var staleRevision = make(PROJECT, FORM, "households.csv", 4L,
                "entityList", NEW, 1L).orElseThrow();
        for (var candidate : new EntityListSnapshotEvidence.Snapshot[] {
                otherProject, otherForm, otherList, staleRevision
        }) {
            assertTrue(EntityListSnapshotEvidence.reviewMissingIds(old, OLD, candidate, NEW).isEmpty());
        }
    }

    @Test
    void rejectsChangedEvidenceBytesWrongRowCountAndMissingRevision() throws Exception {
        assertTrue(make(PROJECT, FORM, "households.csv", 1L, "entityList", OLD, 1L).isEmpty());
        assertTrue(make(PROJECT, FORM, "households.csv", 0L, "entityList", OLD, 2L).isEmpty());
        assertTrue(make(PROJECT, FORM, "households.csv", 1L, "entityList", OLD, null).isEmpty());

        var old = make(PROJECT, FORM, "households.csv", 1L, "entityList", OLD, 2L).orElseThrow();
        var next = make(PROJECT, FORM, "households.csv", 2L, "entityList", NEW, 1L).orElseThrow();
        assertTrue(EntityListSnapshotEvidence.reviewMissingIds(old, NEW, next, NEW).isEmpty());
        var forged = new EntityListSnapshotEvidence.Snapshot(
                PROJECT, FORM, "households.csv", "entityList", 2L,
                next.md5(), "00".repeat(32), 1L);
        assertTrue(EntityListSnapshotEvidence.reviewMissingIds(old, OLD, forged, NEW).isEmpty());
    }

    @Test
    void comparesApprovalOnlyWithSameApprovalType() throws Exception {
        var old = make(PROJECT, FORM, "households.csv", 1L,
                "approvalEntityList", OLD, 2L).orElseThrow();
        var next = make(PROJECT, FORM, "households.csv", 2L,
                "approvalEntityList", NEW, 1L).orElseThrow();
        var regular = make(PROJECT, FORM, "households.csv", 2L,
                "entityList", NEW, 1L).orElseThrow();
        assertEquals(Optional.of(Set.of(UUID.fromString(ID_2))),
                EntityListSnapshotEvidence.reviewMissingIds(old, OLD, next, NEW));
        assertTrue(EntityListSnapshotEvidence.reviewMissingIds(old, OLD, regular, NEW).isEmpty());
    }
}
