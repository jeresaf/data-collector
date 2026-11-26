package org.odk.platform.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProtectedSubmissionCommitmentTest {
    private static UUID id(int n) {
        return UUID.fromString("00000000-0000-4000-8000-" + String.format(java.util.Locale.ROOT, "%012d", n));
    }

    private static ProtectedSubmissionCommitment.Binding binding(int attempt) {
        return new ProtectedSubmissionCommitment.Binding(id(1), id(2), id(3), id(4), id(5),
                id(attempt), "uuid:" + id(7), RecordWorkService.WorkType.FOLLOW_UP);
    }

    private static LinkedMultiValueMap<String, MultipartFile> files(boolean reversed, String xml, byte[] media) {
        var parts = new LinkedMultiValueMap<String, MultipartFile>();
        parts.add("xml_submission_file", new MockMultipartFile("xml_submission_file", "submission.xml",
                "text/xml", xml.getBytes(StandardCharsets.UTF_8)));
        var a = new MockMultipartFile("a.png", "a.png", "image/png", media);
        var b = new MockMultipartFile("b.jpg", "b.jpg", "image/jpeg", new byte[]{4, 5, 6});
        if (reversed) {
            parts.add("b.jpg", b);
            parts.add("a.png", a);
        } else {
            parts.add("a.png", a);
            parts.add("b.jpg", b);
        }
        return parts;
    }

    @Test
    void stableAcrossMultipartOrderButBindsEveryContentAndIdentityField() throws Exception {
        var original = files(false, "<data>é</data>", new byte[]{1, 2, 3});
        String digest = ProtectedSubmissionCommitment.fromMultipart(binding(6), original);
        assertTrue(digest.matches("[0-9a-f]{64}"));
        assertEquals(digest, ProtectedSubmissionCommitment.fromMultipart(binding(6),
                files(true, "<data>é</data>", new byte[]{1, 2, 3})));
        assertNotEquals(digest, ProtectedSubmissionCommitment.fromMultipart(binding(8), original));
        assertNotEquals(digest, ProtectedSubmissionCommitment.fromMultipart(binding(6),
                files(false, "<data>e</data>", new byte[]{1, 2, 3})));
        assertNotEquals(digest, ProtectedSubmissionCommitment.fromMultipart(binding(6),
                files(false, "<data>é</data>", new byte[]{1, 2, 4})));
        original.set("a.png", new MockMultipartFile("a.png", "a.png", "image/jpeg", new byte[]{1,2,3}));
        assertNotEquals(digest, ProtectedSubmissionCommitment.fromMultipart(binding(6), original));
    }

    @Test
    void rejectsDuplicateMissingMalformedOrOversizedParts() {
        var duplicate = files(false, "<data/>", new byte[]{1});
        duplicate.add("a.png", duplicate.getFirst("a.png"));
        assertThrows(IllegalArgumentException.class, () -> ProtectedSubmissionCommitment.fromMultipart(binding(6), duplicate));
        var wrong = files(false, "<data/>", new byte[]{1});
        wrong.set("a.png", new MockMultipartFile("a.png", "../a.png", "image/png", new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> ProtectedSubmissionCommitment.fromMultipart(binding(6), wrong));
        var missing = files(false, "<data/>", new byte[]{1});
        missing.remove("xml_submission_file");
        assertThrows(IllegalArgumentException.class, () -> ProtectedSubmissionCommitment.fromMultipart(binding(6), missing));
        var encoding = files(false, "<data/>", new byte[]{1});
        encoding.set("xml_submission_file", new MockMultipartFile("xml_submission_file", "submission.xml",
                "text/xml", new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(IllegalArgumentException.class, () -> ProtectedSubmissionCommitment.fromMultipart(binding(6), encoding));
        var oversized = files(false, "<data/>", new byte[1_500_001]);
        assertThrows(IllegalArgumentException.class, () -> ProtectedSubmissionCommitment.fromMultipart(binding(6), oversized));
        var empty = files(false, "<data/>", new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> ProtectedSubmissionCommitment.fromMultipart(binding(6), empty));
    }
}
