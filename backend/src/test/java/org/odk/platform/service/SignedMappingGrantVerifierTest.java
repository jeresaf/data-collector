package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Synthetic Ed25519 keys and synthetic record context only. There is NO
 * production issuer, key configuration or activation in M3D4C1.
 */
class SignedMappingGrantVerifierTest {
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private KeyPair key() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private SignedMappingGrantVerifier.Grant grant() {
        return new SignedMappingGrantVerifier.Grant(
            UUID.fromString("45bc4c79-53f4-4faf-803a-48c39cd919d6"),
            UUID.fromString("7241d491-982c-40ae-954b-15c321253c55"),
            UUID.fromString("599efc3b-9a71-4c92-973b-11ad21820117"),
            UUID.fromString("5ad6ce1e-5d39-436f-b3ef-b51c4f3cc4b0"),
            UUID.fromString("ce12893a-91b7-4405-bb36-21a8339283c2"),
            UUID.fromString("238250e6-cd8a-4bdb-9548-910240a7ab67"),
            UUID.fromString("55582fc1-876e-4c1b-8a82-1f6fc2632410"),
            UUID.fromString("d368c53e-134e-42f3-8df2-8500c6502e0e"),
            "uuid:be3d5c56-2bdc-4b5c-9886-91d9d761e541",
            "a".repeat(64), "b".repeat(64), "c".repeat(64),
            "d".repeat(64), "synthetic-key-id-2026",
            NOW.minusSeconds(120), NOW.minusSeconds(60), NOW.plusSeconds(3600),
            List.of(
                new SignedMappingGrantVerifier.FieldRule(
                    "/data/age", SignedMappingGrantVerifier.NativeType.INTEGER),
                new SignedMappingGrantVerifier.FieldRule(
                    "/data/name", SignedMappingGrantVerifier.NativeType.TEXT)));
    }

    private SignedMappingGrantVerifier.ExpectedContext expect(
            SignedMappingGrantVerifier.Grant g) {
        return new SignedMappingGrantVerifier.ExpectedContext(
            g.projectId(), g.workId(), g.recommendationId(),
            g.sourceSubmissionId(), g.targetFormVersionId(),
            g.originalReviewerId(), g.finalSignerId(),
            g.sourceInstanceId(), g.sourceXmlSha256(),
            g.sourceXFormSha256(), g.targetXFormSha256(),
            g.recommendationEvidenceSha256(), g.signingKeyId(), g.fieldRules());
    }

    private SignedMappingGrantVerifier.SignedGrant sign(
            SignedMappingGrantVerifier.Grant g, KeyPair keys) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(SignedMappingGrantVerifier.canonicalBytes(g));
        return new SignedMappingGrantVerifier.SignedGrant(
            g, Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signer.sign()));
    }

    private SignedMappingGrantVerifier.SignatureEvidence check(
            SignedMappingGrantVerifier.SignedGrant signed, KeyPair keys,
            SignedMappingGrantVerifier.ExpectedContext expected,
            SignedMappingGrantVerifier.RevocationCheck revocation) {
        return SignedMappingGrantVerifier.inspect(
            signed, keys.getPublic(), expected, NOW, revocation);
    }

    @Test
    void cryptographicallyValidScopeStillGrantsNoOperationalRights() throws Exception {
        var keys = key();
        var grant = grant();
        var signed = sign(grant, keys);
        var e = check(signed, keys, expect(grant), (id, key) -> false);
        assertEquals("VERIFIED_SIGNATURE_ONLY_NO_OPERATIONAL_GRANT", e.state());
        assertEquals(grant.grantId(), e.grantId());
        assertFalse(e.canReadHistoricalAnswers());
        assertFalse(e.canTransfer());
        assertFalse(e.canFinalize());
        assertFalse(e.canSubmit());
        assertArrayEquals(
            SignedMappingGrantVerifier.canonicalBytes(grant),
            SignedMappingGrantVerifier.canonicalBytes(grant));
    }

    @Test
    void differentSignerKeyIsNotTrusted() throws Exception {
        var original = key();
        var unrelated = key();
        var grant = grant();
        var e = check(sign(grant, original), unrelated,
            expect(grant), (id, key) -> false);
        assertEquals("BLOCKED_BAD_SIGNATURE", e.state());
    }

    @Test
    void modifyingSignedScopeEvenOneFieldTypeInvalidatesSignature() throws Exception {
        var original = key();
        var grant = grant();
        var signed = sign(grant, original);
        var changed = withRules(grant, List.of(
            new SignedMappingGrantVerifier.FieldRule(
                "/data/age", SignedMappingGrantVerifier.NativeType.DECIMAL),
            new SignedMappingGrantVerifier.FieldRule(
                "/data/name", SignedMappingGrantVerifier.NativeType.TEXT)));
        var tampered = new SignedMappingGrantVerifier.SignedGrant(
            changed, signed.signatureBase64Url());
        assertEquals("BLOCKED_BAD_SIGNATURE",
            check(tampered, original, expect(grant),
                (id, key) -> false).state());
    }

    @Test
    void validSignedScopeFromWrongWorkIsRejectedAgainstFreshServerContext()
            throws Exception {
        var keys = key();
        var grant = grant();
        var differentWork = new SignedMappingGrantVerifier.ExpectedContext(
            grant.projectId(), UUID.randomUUID(), grant.recommendationId(),
            grant.sourceSubmissionId(), grant.targetFormVersionId(),
            grant.originalReviewerId(), grant.finalSignerId(),
            grant.sourceInstanceId(), grant.sourceXmlSha256(),
            grant.sourceXFormSha256(), grant.targetXFormSha256(),
            grant.recommendationEvidenceSha256(),
            grant.signingKeyId(), grant.fieldRules());
        assertEquals("BLOCKED_CONTEXT_OR_SCOPE_DRIFT",
            check(sign(grant, keys), keys, differentWork,
                (id, key) -> false).state());
        var wrongKeyId = new SignedMappingGrantVerifier.ExpectedContext(
            grant.projectId(), grant.workId(), grant.recommendationId(),
            grant.sourceSubmissionId(), grant.targetFormVersionId(),
            grant.originalReviewerId(), grant.finalSignerId(),
            grant.sourceInstanceId(), grant.sourceXmlSha256(),
            grant.sourceXFormSha256(), grant.targetXFormSha256(),
            grant.recommendationEvidenceSha256(), "revoked-key-id",
            grant.fieldRules());
        assertEquals("BLOCKED_CONTEXT_OR_SCOPE_DRIFT",
            check(sign(grant, keys), keys, wrongKeyId,
                (id, key) -> false).state());
    }

    @Test
    void validSignatureCannotBypassRevocationOrFailedRevocationService()
            throws Exception {
        var keys = key();
        var grant = grant();
        var signed = sign(grant, keys);
        assertEquals("BLOCKED_REVOKED_OR_DENIED",
            check(signed, keys, expect(grant),
                (id, key) -> id.equals(grant.grantId())).state());
        assertEquals("BLOCKED_REVOCATION_UNAVAILABLE",
            check(signed, keys, expect(grant),
                (id, key) -> { throw new IllegalStateException("offline"); }).state());
        assertEquals("BLOCKED_MISSING_SERVER_VERIFICATION_DEPENDENCY",
            check(signed, keys, expect(grant), null).state());
        assertEquals("BLOCKED_MISSING_SERVER_VERIFICATION_DEPENDENCY",
            SignedMappingGrantVerifier.inspect(
                signed, null, expect(grant), NOW, (id, key) -> false).state());
    }

    @Test
    void notYetValidOrExpiredScopeIsRejectedDespiteValidSignature()
            throws Exception {
        var keys = key();
        var grant = grant();
        var future = withTime(grant, NOW.plusSeconds(10),
            NOW.plusSeconds(11), NOW.plusSeconds(3600));
        assertEquals("BLOCKED_NOT_YET_VALID_OR_EXPIRED",
            check(sign(future, keys), keys, expect(future),
                (id, key) -> false).state());
        var expired = withTime(grant, NOW.minusSeconds(3600),
            NOW.minusSeconds(3599), NOW);
        assertEquals("BLOCKED_NOT_YET_VALID_OR_EXPIRED",
            check(sign(expired, keys), keys, expect(expired),
                (id, key) -> false).state());
    }

    @Test
    void overlappingSelfReviewerUnsortedAndOverlongGrantsAreUnconstructable() {
        var grant = grant();
        assertThrows(IllegalArgumentException.class, () ->
            new SignedMappingGrantVerifier.FieldRule("/data/meta/instanceID",
                SignedMappingGrantVerifier.NativeType.TEXT));
        assertThrows(IllegalArgumentException.class, () ->
            new SignedMappingGrantVerifier.FieldRule("../age",
                SignedMappingGrantVerifier.NativeType.INTEGER));
        assertThrows(IllegalArgumentException.class, () ->
            withRules(grant, List.of(grant.fieldRules().get(1),
                grant.fieldRules().get(0))));
        assertThrows(IllegalArgumentException.class, () ->
            withRules(grant, List.of(grant.fieldRules().get(0),
                grant.fieldRules().get(0))));
        assertThrows(IllegalArgumentException.class, () ->
            withTime(grant, NOW, NOW, NOW.plusSeconds(86401)));
        assertThrows(IllegalArgumentException.class, () ->
            withTime(grant, NOW, NOW.minusSeconds(1), NOW.plusSeconds(3600)));
        assertThrows(IllegalArgumentException.class, () ->
            new SignedMappingGrantVerifier.Grant(
                grant.grantId(), grant.projectId(), grant.workId(),
                grant.recommendationId(), grant.sourceSubmissionId(),
                grant.targetFormVersionId(), grant.originalReviewerId(),
                grant.originalReviewerId(), grant.sourceInstanceId(),
                grant.sourceXmlSha256(), grant.sourceXFormSha256(),
                grant.targetXFormSha256(), grant.recommendationEvidenceSha256(),
                grant.signingKeyId(), grant.issuedAt(),
                grant.notBefore(), grant.expiresAt(), grant.fieldRules()));
    }

    @Test
    void signatureRecordRejectsNonCanonicalOrShortEncodedSignatures() {
        var grant = grant();
        assertThrows(IllegalArgumentException.class, () ->
            new SignedMappingGrantVerifier.SignedGrant(grant, "a"));
        assertThrows(IllegalArgumentException.class, () ->
            new SignedMappingGrantVerifier.SignedGrant(grant, "$".repeat(86)));
    }

    @Test
    void signatureEvidenceCannotBeFabricatedToEnableMigration() {
        assertThrows(IllegalArgumentException.class, () ->
            new SignedMappingGrantVerifier.SignatureEvidence(
                UUID.randomUUID(), "VERIFIED_SIGNATURE_ONLY_NO_OPERATIONAL_GRANT",
                true, false, false, false));
        assertThrows(IllegalArgumentException.class, () ->
            new SignedMappingGrantVerifier.SignatureEvidence(
                UUID.randomUUID(), "VERIFIED_SIGNATURE_ONLY_NO_OPERATIONAL_GRANT",
                false, true, false, false));
        assertThrows(IllegalArgumentException.class, () ->
            new SignedMappingGrantVerifier.SignatureEvidence(
                UUID.randomUUID(), "VERIFIED_SIGNATURE_ONLY_NO_OPERATIONAL_GRANT",
                false, false, false, true));
    }

    private SignedMappingGrantVerifier.Grant withRules(
            SignedMappingGrantVerifier.Grant g,
            List<SignedMappingGrantVerifier.FieldRule> rules) {
        return new SignedMappingGrantVerifier.Grant(
            g.grantId(), g.projectId(), g.workId(), g.recommendationId(),
            g.sourceSubmissionId(), g.targetFormVersionId(),
            g.originalReviewerId(), g.finalSignerId(), g.sourceInstanceId(),
            g.sourceXmlSha256(), g.sourceXFormSha256(), g.targetXFormSha256(),
            g.recommendationEvidenceSha256(), g.signingKeyId(),
            g.issuedAt(), g.notBefore(), g.expiresAt(), rules);
    }

    private SignedMappingGrantVerifier.Grant withTime(
            SignedMappingGrantVerifier.Grant g,
            Instant issuedAt, Instant validFrom, Instant validUntil) {
        return new SignedMappingGrantVerifier.Grant(
            g.grantId(), g.projectId(), g.workId(), g.recommendationId(),
            g.sourceSubmissionId(), g.targetFormVersionId(),
            g.originalReviewerId(), g.finalSignerId(), g.sourceInstanceId(),
            g.sourceXmlSha256(), g.sourceXFormSha256(), g.targetXFormSha256(),
            g.recommendationEvidenceSha256(), g.signingKeyId(),
            issuedAt, validFrom, validUntil, g.fieldRules());
    }
}
