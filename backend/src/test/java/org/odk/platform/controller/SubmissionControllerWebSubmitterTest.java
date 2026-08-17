package org.odk.platform.controller;

import org.junit.jupiter.api.Test;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.Submission;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubmissionControllerWebSubmitterTest {

    private final SubmissionController controller = new SubmissionController(null, null, null);

    @Test
    void webUserSubmitterFallsBackToSubmissionMetadata() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("submittedByType", "USER");
        metadata.put("submittedById", userId.toString());
        metadata.put("submittedByName", "aikojerry");
        metadata.put("submittedByDisplayName", "Aiko Jerry");

        Submission submission = Submission.builder()
                .metadata(metadata)
                .build();

        UUID resolvedId = ReflectionTestUtils.invokeMethod(
                controller, "resolveSubmittedById", submission);
        String resolvedName = ReflectionTestUtils.invokeMethod(
                controller, "resolveSubmittedByName", submission);

        assertEquals(userId, resolvedId);
        assertEquals("Aiko Jerry", resolvedName);
    }

    @Test
    void appUserRelationshipStillTakesPrecedence() {
        UUID appUserId = UUID.randomUUID();
        AppUser appUser = new AppUser();
        appUser.setId(appUserId);
        appUser.setUsername("collect_user");

        Submission submission = Submission.builder()
                .submittedBy(appUser)
                .metadata(Map.of(
                        "submittedById", UUID.randomUUID().toString(),
                        "submittedByName", "web_user"))
                .build();

        UUID resolvedId = ReflectionTestUtils.invokeMethod(
                controller, "resolveSubmittedById", submission);
        String resolvedName = ReflectionTestUtils.invokeMethod(
                controller, "resolveSubmittedByName", submission);

        assertEquals(appUserId, resolvedId);
        assertEquals("collect_user", resolvedName);
    }
}
