package org.odk.platform.openrosa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EntityListRemovalReviewRequestTest {
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID FORM = UUID.fromString("00000000-0000-0000-0000-000000000502");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000503");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000504");
    private static final UUID FIRST_ID = UUID.fromString("027d7ac0-224c-43a2-85e7-862a046f8c21");
    private static final UUID SECOND_ID = UUID.fromString("1a27b2c2-9427-4f17-9bce-b5b774563219");

    private static byte[] csv(String rows) {
        return ("name,label,__version\n" + rows).getBytes(StandardCharsets.UTF_8);
    }

    private static EntityListSnapshotLedgerCoordinator.Head head(
            long revision, byte[] bytes, UUID project) throws Exception {
        String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        var proof = EntityListSnapshotEvidence.fromVerifiedBytes(
                project, FORM, "households.csv", revision,
                Map.of("odkMediaType", "entityList"), bytes, md5,
                (long) (new String(bytes, StandardCharsets.UTF_8).lines().count() - 1))
                .orElseThrow();
        return new EntityListSnapshotLedgerCoordinator.Head(proof, bytes);
    }

    @Test
    void removalRequiresIndependentReviewAndPreservesExactEvidence() throws Exception {
        var before = head(1L, csv(FIRST_ID + ",First,1\n" + SECOND_ID + ",Second,1\n"), PROJECT);
        var proposed = head(2L, csv(FIRST_ID + ",First,2\n"), PROJECT);

        var request = EntityListRemovalReviewRequest.prepare(
                ACTOR, "Approved dataset cleanup pending independent review", before, proposed)
                .orElseThrow();
        assertEquals(ACTOR, request.requestedBy());
        assertEquals(1L, request.expectedHead().revision());
        assertEquals(2L, request.candidate().revision());
        assertEquals(before.evidence().sha256(), request.expectedHead().sha256());
        assertEquals(proposed.evidence().sha256(), request.candidate().sha256());
        assertEquals(java.util.Set.of(SECOND_ID), request.missingIds());
        assertTrue(EntityListRemovalReviewRequest.matchesForReview(request, ACTOR, before, proposed));
        assertFalse(EntityListRemovalReviewRequest.matchesForReview(request, OTHER, before, proposed));
        assertThrows(UnsupportedOperationException.class,
                () -> request.missingIds().clear());
    }

    @Test
    void changedHeadOrCandidateInvalidatesPreparedRequest() throws Exception {
        var before = head(1L, csv(FIRST_ID + ",First,1\n" + SECOND_ID + ",Second,1\n"), PROJECT);
        var candidate = head(2L, csv(FIRST_ID + ",First,2\n"), PROJECT);
        var request = EntityListRemovalReviewRequest.prepare(
                ACTOR, "Reason", before, candidate).orElseThrow();
        var revisedHead = head(2L, csv(FIRST_ID + ",First,2\n" + SECOND_ID + ",Second,2\n"), PROJECT);
        var changedCandidate = head(2L, csv(FIRST_ID + ",Edited,2\n"), PROJECT);
        assertFalse(EntityListRemovalReviewRequest.matchesForReview(
                request, ACTOR, revisedHead, candidate));
        assertFalse(EntityListRemovalReviewRequest.matchesForReview(
                request, ACTOR, before, changedCandidate));
    }

    @Test
    void rejectsNoRemovalWrongScopeBrokenProofOrMissingJustification() throws Exception {
        var before = head(1L, csv(FIRST_ID + ",First,1\n" + SECOND_ID + ",Second,1\n"), PROJECT);
        var unchangedIds = head(2L, csv(FIRST_ID + ",First,2\n" + SECOND_ID + ",Second,2\n"), PROJECT);
        var candidate = head(2L, csv(FIRST_ID + ",First,2\n"), PROJECT);
        assertTrue(EntityListRemovalReviewRequest.prepare(
                ACTOR, "Reason", before, unchangedIds).isEmpty());
        assertTrue(EntityListRemovalReviewRequest.prepare(
                ACTOR, "Reason", before, head(2L, csv(FIRST_ID + ",First,2\n"), OTHER)).isEmpty());
        assertTrue(EntityListRemovalReviewRequest.prepare(
                ACTOR, " ", before, candidate).isEmpty());
        assertTrue(EntityListRemovalReviewRequest.prepare(
                null, "Reason", before, candidate).isEmpty());
        assertTrue(EntityListRemovalReviewRequest.prepare(
                ACTOR, "x".repeat(2001), before, candidate).isEmpty());

        var forged = new EntityListSnapshotLedgerCoordinator.Head(
                candidate.evidence(), csv(FIRST_ID + ",Tampered,2\n"));
        assertTrue(EntityListRemovalReviewRequest.prepare(
                ACTOR, "Reason", before, forged).isEmpty());
    }
}
