package org.odk.platform.openrosa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityListSnapshotRevisionPolicyTest {
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID OTHER_PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID FORM = UUID.fromString("00000000-0000-0000-0000-000000000303");
    private static final UUID OTHER_FORM = UUID.fromString("00000000-0000-0000-0000-000000000304");
    private static final String ID1 = "d6b8a5e0-79d8-46e7-aab9-5bf1a57162ad";
    private static final String ID2 = "34f2ce6b-34e3-49e0-9db8-5c194fca56da";
    private static final byte[] ORIGINAL = ("name,label,__version\n" + ID1 + ",First,1\n")
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] EXTENDED = ("name,label,__version\n"
            + ID1 + ",First,2\n" + ID2 + ",Second,1\n").getBytes(StandardCharsets.UTF_8);
    private static final byte[] REMOVED = ("name,label,__version\n" + ID1 + ",First,3\n")
            .getBytes(StandardCharsets.UTF_8);

    private static EntityListSnapshotEvidence.Snapshot evidence(
            UUID project, UUID form, String filename, String type,
            long revision, byte[] content, long rowCount) throws Exception {
        String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
        return EntityListSnapshotEvidence.fromVerifiedBytes(project, form,
                filename, revision, Map.of("odkMediaType", type), content, md5, rowCount)
                .orElseThrow();
    }

    private static EntityListSnapshotEvidence.Snapshot at(
            long revision, byte[] content, long count) throws Exception {
        return evidence(PROJECT, FORM, "households.csv", "entityList", revision, content, count);
    }

    @Test
    void consecutiveAdditionsCanBeProposedButNotPersisted() throws Exception {
        var assessment = EntityListSnapshotRevisionPolicy.assess(
                at(1, ORIGINAL, 1), ORIGINAL, at(2, EXTENDED, 2), EXTENDED);
        assertEquals(EntityListSnapshotRevisionPolicy.Status.READY_FOR_LEDGER_APPEND,
                assessment.status());
        assertEquals(2, assessment.proposedRevision());
        assertTrue(assessment.missingIds().isEmpty());
    }

    @Test
    void identicalRetryIsInertAndProposedRemovalIsNeverAutoAdmitted() throws Exception {
        var head = at(3, EXTENDED, 2);
        assertEquals(EntityListSnapshotRevisionPolicy.Status.UNCHANGED,
                EntityListSnapshotRevisionPolicy.assess(head, EXTENDED, head, EXTENDED).status());
        var removal = EntityListSnapshotRevisionPolicy.assess(
                head, EXTENDED, at(4, REMOVED, 1), REMOVED);
        assertEquals(EntityListSnapshotRevisionPolicy.Status.REQUIRES_REMOVAL_APPROVAL,
                removal.status());
        assertEquals(Set.of(UUID.fromString(ID2)), removal.missingIds());
    }

    @Test
    void deniesStaleSkippedAndOverflowedRevisions() throws Exception {
        var head = at(4, ORIGINAL, 1);
        for (long revision : new long[] {2, 4, 6}) {
            assertEquals(EntityListSnapshotRevisionPolicy.Status.CONFLICT_OR_UNTRUSTED,
                    EntityListSnapshotRevisionPolicy.assess(
                            head, ORIGINAL, at(revision, EXTENDED, 2), EXTENDED).status());
        }
        var maximum = at(Long.MAX_VALUE, ORIGINAL, 1);
        assertEquals(EntityListSnapshotRevisionPolicy.Status.CONFLICT_OR_UNTRUSTED,
                EntityListSnapshotRevisionPolicy.assess(
                        maximum, ORIGINAL, at(1, EXTENDED, 2), EXTENDED).status());
    }

    @Test
    void deniesDifferentProjectFormListTypeAndMutatedEvidence() throws Exception {
        var head = at(1, ORIGINAL, 1);
        var otherProject = evidence(OTHER_PROJECT, FORM, "households.csv",
                "entityList", 2, EXTENDED, 2);
        var otherForm = evidence(PROJECT, OTHER_FORM, "households.csv",
                "entityList", 2, EXTENDED, 2);
        var otherList = evidence(PROJECT, FORM, "villages.csv",
                "entityList", 2, EXTENDED, 2);
        var otherType = evidence(PROJECT, FORM, "households.csv",
                "approvalEntityList", 2, EXTENDED, 2);
        for (var proposed : new EntityListSnapshotEvidence.Snapshot[] {
                otherProject, otherForm, otherList, otherType
        }) {
            assertEquals(EntityListSnapshotRevisionPolicy.Status.CONFLICT_OR_UNTRUSTED,
                    EntityListSnapshotRevisionPolicy.assess(
                            head, ORIGINAL, proposed, EXTENDED).status());
        }
        assertEquals(EntityListSnapshotRevisionPolicy.Status.CONFLICT_OR_UNTRUSTED,
                EntityListSnapshotRevisionPolicy.assess(
                        head, EXTENDED, at(2, EXTENDED, 2), EXTENDED).status());
        assertEquals(EntityListSnapshotRevisionPolicy.Status.CONFLICT_OR_UNTRUSTED,
                EntityListSnapshotRevisionPolicy.assess(
                        head, ORIGINAL, at(2, EXTENDED, 2), ORIGINAL).status());
    }
}
