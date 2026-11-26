package org.odk.platform.openrosa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityListTransitionReviewTest {
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID FORM = UUID.fromString("00000000-0000-0000-0000-000000000303");
    private static final String FIRST_ID = "d6b8a5e0-79d8-46e7-aab9-5bf1a57162ad";
    private static final String SECOND_ID = "34f2ce6b-34e3-49e0-9db8-5c194fca56da";

    private static byte[] csv(String... rows) {
        return ("name,label,__version\n" + String.join("", rows))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String row(String id, int version) {
        return id + ",Label," + version + "\n";
    }

    private static EntityListSnapshotEvidence.Snapshot evidence(byte[] bytes,
                                                                  long revision,
                                                                  long rowCount) throws Exception {
        String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        return EntityListSnapshotEvidence.fromVerifiedBytes(
                PROJECT, FORM, "households.csv", revision,
                Map.of("odkMediaType", "entityList"), bytes, md5, rowCount).orElseThrow();
    }

    @Test
    void noRemovalsAreObservationalNotApproval() throws Exception {
        byte[] old = csv(row(FIRST_ID, 1));
        byte[] next = csv(row(FIRST_ID, 2), row(SECOND_ID, 1));
        var review = EntityListTransitionReview.inspect(
                evidence(old, 1, 1), old, evidence(next, 2, 2), next).orElseThrow();
        assertEquals(EntityListTransitionReview.Finding.NO_REMOVALS_OBSERVED, review.finding());
        assertTrue(review.missingIds().isEmpty());
        assertEquals(1, review.previousRowCount());
        assertEquals(2, review.nextRowCount());
    }

    @Test
    void anyRemovalRequiresReviewEvenBelowBulkThreshold() throws Exception {
        StringBuilder previousRows = new StringBuilder();
        StringBuilder nextRows = new StringBuilder();
        for (int i = 1; i <= 8; i++) {
            String id = String.format("00000000-0000-4000-8000-%012d", i);
            previousRows.append(row(id, 1));
            if (i != 8) {
                nextRows.append(row(id, 2));
            }
        }
        byte[] old = csv(previousRows.toString());
        byte[] next = csv(nextRows.toString());
        var review = EntityListTransitionReview.inspect(
                evidence(old, 1, 8), old, evidence(next, 2, 7), next).orElseThrow();
        assertEquals(EntityListTransitionReview.Finding.REMOVAL_REVIEW_REQUIRED, review.finding());
        assertEquals(1, review.missingIds().size());
    }

    @Test
    void bulkRemovalIsWarningNotAuthority() throws Exception {
        byte[] old = csv(row(FIRST_ID, 1), row(SECOND_ID, 1));
        byte[] next = csv(row(FIRST_ID, 2));
        var review = EntityListTransitionReview.inspect(
                evidence(old, 1, 2), old, evidence(next, 2, 1), next).orElseThrow();
        assertEquals(EntityListTransitionReview.Finding.BULK_REMOVAL_REVIEW_REQUIRED, review.finding());
        assertEquals(java.util.Set.of(UUID.fromString(SECOND_ID)), review.missingIds());
    }

    @Test
    void invalidOrUntrustedSnapshotDoesNotProduceAReview() throws Exception {
        byte[] old = csv(row(FIRST_ID, 1), row(SECOND_ID, 1));
        byte[] next = csv(row(FIRST_ID, 2));
        var before = evidence(old, 1, 2);
        var after = evidence(next, 2, 1);
        assertTrue(EntityListTransitionReview.inspect(before, next, after, next).isEmpty());
        assertTrue(EntityListTransitionReview.inspect(before, old, after, old).isEmpty());
        assertTrue(EntityListTransitionReview.inspect(before, old, before, old).isEmpty());
        assertTrue(EntityListTransitionReview.inspect(null, old, after, next).isEmpty());
    }
}
