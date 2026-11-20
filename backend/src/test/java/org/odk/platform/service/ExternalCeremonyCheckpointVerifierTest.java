package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Ephemeral test-only verifier keys. Never a production log or HSM. */
class ExternalCeremonyCheckpointVerifierTest {
    private static final Instant WITNESSED =
            Instant.parse("2026-11-20T11:59:31.123456Z");
    private static final Instant CHECKPOINT =
            Instant.parse("2026-11-20T12:00:00Z");
    private static final Instant ATTESTED =
            Instant.parse("2026-11-20T12:01:00Z");
    private static final Instant NOW =
            Instant.parse("2026-11-20T12:05:00Z");
    private static final String LOG_ID = "external-independent-ceremony-log";
    private static final String LOG_KEY_ID = "independent-log-verifier-01";
    private static final String ATTESTER_KEY_ID = "external-attestor-verifier-01";

    private record Scenario(
            KeyPair attestor, KeyPair log,
            TypedMappingOfflineCeremonyService.CeremonyPacket packet,
            ExternalCeremonyAttestationVerifier.SignedAttestation signedAttestation,
            ExternalCeremonyCheckpointVerifier.SignedCheckpoint signedCheckpoint,
            ExternalCeremonyCheckpointVerifier.InclusionProof proof,
            ExternalCeremonyCheckpointVerifier.TrustedLogKey pinnedLogKey) {}

    private static KeyPair ephemeral() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    private static byte[] digest(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    private static String digestHex(byte[] bytes) throws Exception {
        return hex(digest(bytes));
    }

    private static String signedBase64(KeyPair keys, byte[] bytes) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(bytes);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signer.sign());
    }

    private static TypedMappingOfflineCeremonyService.CeremonyPacket packet() {
        return new TypedMappingOfflineCeremonyService.CeremonyPacket(
                UUID.fromString("fe15dbdf-65e7-4b19-9ec4-d6b58000c112"),
                UUID.fromString("4ffce42d-23eb-4ff2-b4f7-f587e514999c"),
                "untrusted-rotation-2026",
                UUID.fromString("9fa8ed02-43ce-4c86-9b98-d2576b942789"),
                UUID.fromString("90f451dd-8916-42f0-ae68-02e8aee0d1f7"),
                UUID.fromString("ac086c9d-1c14-4f6a-a019-cd5ab3f2503b"),
                "witness-test-id-001",
                "a".repeat(64), "b".repeat(64),
                "c".repeat(64), "d".repeat(64),
                "offline-checkpoint-001", "e".repeat(64), WITNESSED,
                "EVIDENCE_COMMITTED_PENDING_EXTERNAL_ATTESTATION_NO_TRUST",
                false, false, false, false, false);
    }

    private static ExternalCeremonyAttestationVerifier.Attestation attestation(
            String checkpointHash) {
        var p = packet();
        return new ExternalCeremonyAttestationVerifier.Attestation(
                p.projectId(), p.proposalId(), p.proposerId(), p.reviewerId(),
                p.witnessId(),
                UUID.fromString("faf5596e-526a-41d8-8f3c-453e4d36ccda"),
                p.proposedKeyId(), p.observedKeySha256(),
                p.proposalEvidenceSha256(), p.reviewEvidenceSha256(),
                p.externalBundleSha256(), p.externalReference(),
                p.commitmentSha256(), ATTESTER_KEY_ID,
                checkpointHash, p.recordedAt(), ATTESTED,
                ATTESTED.plusSeconds(86400));
    }

    private static ExternalCeremonyAttestationVerifier.SignedAttestation
            signAttestation(KeyPair attestor,
                            ExternalCeremonyAttestationVerifier.Attestation a)
            throws Exception {
        return new ExternalCeremonyAttestationVerifier.SignedAttestation(
                a, signedBase64(attestor,
                ExternalCeremonyAttestationVerifier.canonicalBytes(a)));
    }

    private static ExternalCeremonyCheckpointVerifier.SignedCheckpoint
            signCheckpoint(KeyPair log, ExternalCeremonyCheckpointVerifier.Checkpoint c)
            throws Exception {
        return new ExternalCeremonyCheckpointVerifier.SignedCheckpoint(
                c, signedBase64(log,
                ExternalCeremonyCheckpointVerifier.canonicalCheckpointBytes(c)));
    }

    private static byte[] alternateLeaf(String name) throws Exception {
        byte[] contents = name.getBytes(StandardCharsets.UTF_8);
        byte[] raw = new byte[contents.length + 1];
        System.arraycopy(contents, 0, raw, 1, contents.length);
        return digest(raw);
    }

    private static Scenario scenario() throws Exception {
        KeyPair attestor = ephemeral();
        KeyPair log = ephemeral();
        var unsigned = attestation("0".repeat(64));
        byte[] left = alternateLeaf("other-ceremony-0");
        byte[] middle = ExternalCeremonyCheckpointVerifier.leafHash(unsigned);
        byte[] right = alternateLeaf("other-ceremony-2");

        // RFC6962 tree of 3: root = hash(node(left, middle), right).
        byte[] root = ExternalCeremonyCheckpointVerifier.nodeHash(
                ExternalCeremonyCheckpointVerifier.nodeHash(left, middle), right);
        var c = new ExternalCeremonyCheckpointVerifier.Checkpoint(
                LOG_ID, LOG_KEY_ID, 3, hex(root), CHECKPOINT,
                CHECKPOINT.plusSeconds(3600));
        String checkpointHash = digestHex(
                ExternalCeremonyCheckpointVerifier.canonicalCheckpointBytes(c));
        var signedAttestation = signAttestation(
                attestor, attestation(checkpointHash));
        var proof = new ExternalCeremonyCheckpointVerifier.InclusionProof(
                1, List.of(hex(left), hex(right)));
        var pinned = new ExternalCeremonyCheckpointVerifier.TrustedLogKey(
                LOG_ID, LOG_KEY_ID, log.getPublic(),
                digestHex(log.getPublic().getEncoded()));
        return new Scenario(attestor, log, packet(), signedAttestation,
                signCheckpoint(log, c), proof, pinned);
    }

    private static ExternalCeremonyAttestationVerifier.ExpectedCeremony expected(
            Scenario s) {
        return new ExternalCeremonyAttestationVerifier.ExpectedCeremony(
                s.packet(), ATTESTER_KEY_ID);
    }

    private static ExternalCeremonyCheckpointVerifier.Evidence verify(
            Scenario s, ExternalCeremonyCheckpointVerifier.SignedCheckpoint head,
            ExternalCeremonyCheckpointVerifier.InclusionProof path,
            ExternalCeremonyCheckpointVerifier.TrustedLogKey pinnedKey,
            Instant now,
            ExternalCeremonyCheckpointVerifier.LogRevocationLookup revocation) {
        return ExternalCeremonyCheckpointVerifier.inspect(
                s.signedAttestation(), s.attestor().getPublic(),
                expected(s), (key, proposal) -> false, head, path,
                pinnedKey, now, revocation);
    }

    @Test
    void independentSignedThreeLeafProofIsIncludedButNotPublishedOrTrusted()
            throws Exception {
        var s = scenario();
        var e = verify(s, s.signedCheckpoint(), s.proof(),
                s.pinnedLogKey(), NOW, (log, key, root, proposal) -> false);
        assertEquals(
                "CRYPTOGRAPHIC_INCLUSION_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                e.state());
        assertTrue(e.cryptographicInclusionVerified());
        assertFalse(e.independentlyPublished());
        assertFalse(e.historicalConsistencyVerified());
        assertFalse(e.trustedKeyRegistered());
        assertFalse(e.canIssueGrant());
        assertFalse(e.canReadHistoricalAnswers());
        assertFalse(e.canTransfer());
        assertFalse(e.canSubmit());
        assertEquals(s.packet().proposalId(), e.proposalId());
        assertArrayEquals(
                ExternalCeremonyCheckpointVerifier.canonicalCommitmentLeaf(
                    s.signedAttestation().attestation()),
                ExternalCeremonyCheckpointVerifier.canonicalCommitmentLeaf(
                    attestation("f".repeat(64))),
                "The V7 log leaf is independent of the attestation's checkpoint hash");
    }

    @Test
    void swappedMissingOrExtraSiblingAndWrongLeafIndexFailClosed()
            throws Exception {
        var s = scenario();
        var wrongOrder = new ExternalCeremonyCheckpointVerifier.InclusionProof(
                1, List.of(s.proof().siblingsSha256().get(1),
                        s.proof().siblingsSha256().get(0)));
        var missing = new ExternalCeremonyCheckpointVerifier.InclusionProof(
                1, List.of(s.proof().siblingsSha256().get(0)));
        var extra = new ExternalCeremonyCheckpointVerifier.InclusionProof(
                1, List.of(s.proof().siblingsSha256().get(0),
                        s.proof().siblingsSha256().get(1),
                        s.proof().siblingsSha256().get(0)));
        var wrongIndex = new ExternalCeremonyCheckpointVerifier.InclusionProof(
                2, s.proof().siblingsSha256());
        for (var proof : List.of(wrongOrder, missing, extra, wrongIndex)) {
            assertEquals("BLOCKED_MERKLE_INCLUSION_PROOF",
                    verify(s, s.signedCheckpoint(), proof,
                        s.pinnedLogKey(), NOW,
                        (log, key, root, proposal) -> false).state());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalCeremonyCheckpointVerifier.InclusionProof(
                    -1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalCeremonyCheckpointVerifier.InclusionProof(
                    1, List.of("0".repeat(63))));
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalCeremonyCheckpointVerifier.InclusionProof(
                    1, java.util.Collections.nCopies(65, "0".repeat(64))));
    }

    @Test
    void oneAndTwoLeafProofsAreValidAndLeafOrderingMatters() throws Exception {
        var s = scenario();
        byte[] leaf = ExternalCeremonyCheckpointVerifier.leafHash(
                s.signedAttestation().attestation());
        assertTrue(ExternalCeremonyCheckpointVerifier.validInclusion(
                leaf, 1,
                new ExternalCeremonyCheckpointVerifier.InclusionProof(
                    0, List.of()), leaf));

        byte[] other = alternateLeaf("second");
        byte[] root = ExternalCeremonyCheckpointVerifier.nodeHash(leaf, other);
        assertTrue(ExternalCeremonyCheckpointVerifier.validInclusion(
                leaf, 2,
                new ExternalCeremonyCheckpointVerifier.InclusionProof(
                    0, List.of(hex(other))), root));
        assertTrue(ExternalCeremonyCheckpointVerifier.validInclusion(
                other, 2,
                new ExternalCeremonyCheckpointVerifier.InclusionProof(
                    1, List.of(hex(leaf))), root));
        assertFalse(ExternalCeremonyCheckpointVerifier.validInclusion(
                leaf, 2,
                new ExternalCeremonyCheckpointVerifier.InclusionProof(
                    1, List.of(hex(other))), root));
        assertFalse(ExternalCeremonyCheckpointVerifier.validInclusion(
                leaf, 1,
                new ExternalCeremonyCheckpointVerifier.InclusionProof(
                    0, List.of(hex(other))), leaf));
        assertFalse(ExternalCeremonyCheckpointVerifier.validInclusion(
                leaf, 2,
                new ExternalCeremonyCheckpointVerifier.InclusionProof(
                    2, List.of(hex(other))), root));
    }

    @Test
    void logPublicKeyAndPinnedFingerprintMustAgreeWithoutSharingAttestorKey()
            throws Exception {
        var s = scenario();
        var other = ephemeral();
        var wrongLog = new ExternalCeremonyCheckpointVerifier.TrustedLogKey(
                LOG_ID, LOG_KEY_ID, other.getPublic(),
                digestHex(other.getPublic().getEncoded()));
        assertEquals("BLOCKED_LOG_CHECKPOINT_SIGNATURE",
                verify(s, s.signedCheckpoint(), s.proof(), wrongLog, NOW,
                    (l, k, r, p) -> false).state());

        var wrongPin = new ExternalCeremonyCheckpointVerifier.TrustedLogKey(
                LOG_ID, LOG_KEY_ID, s.log().getPublic(), "0".repeat(64));
        assertEquals("BLOCKED_UNPINNED_OR_SHARED_LOG_KEY",
                verify(s, s.signedCheckpoint(), s.proof(), wrongPin, NOW,
                    (l, k, r, p) -> false).state());

        var sameKey = new ExternalCeremonyCheckpointVerifier.TrustedLogKey(
                LOG_ID, LOG_KEY_ID, s.attestor().getPublic(),
                digestHex(s.attestor().getPublic().getEncoded()));
        assertEquals("BLOCKED_UNPINNED_OR_SHARED_LOG_KEY",
                verify(s, s.signedCheckpoint(), s.proof(), sameKey, NOW,
                    (l, k, r, p) -> false).state());

        var wrongId = new ExternalCeremonyCheckpointVerifier.TrustedLogKey(
                "different-unrelated-log", LOG_KEY_ID, s.log().getPublic(),
                digestHex(s.log().getPublic().getEncoded()));
        assertEquals("BLOCKED_CHECKPOINT_ID_OR_DIGEST_DRIFT",
                verify(s, s.signedCheckpoint(), s.proof(), wrongId, NOW,
                    (l, k, r, p) -> false).state());
    }

    @Test
    void alteredSignedCheckpointCannotMatchTheIndependentAttestation()
            throws Exception {
        var s = scenario();
        var original = s.signedCheckpoint().checkpoint();
        var wrongRoot = new ExternalCeremonyCheckpointVerifier.Checkpoint(
                original.logId(), original.verifierKeyId(), original.treeSize(),
                "f".repeat(64), original.checkpointAt(),
                original.validUntil());
        var tampered = signCheckpoint(s.log(), wrongRoot);
        assertEquals("BLOCKED_CHECKPOINT_ID_OR_DIGEST_DRIFT",
                verify(s, tampered, s.proof(), s.pinnedLogKey(), NOW,
                    (l, k, r, p) -> false).state());
        assertArrayEquals(
                ExternalCeremonyCheckpointVerifier.canonicalCheckpointBytes(
                    original),
                ExternalCeremonyCheckpointVerifier.canonicalCheckpointBytes(
                    original));
    }

    @Test
    void futureExpiredAndUnboundedLogCheckpointsFailClosed()
            throws Exception {
        var s = scenario();
        // The attestation itself is not yet valid before its issued time.
        assertEquals("BLOCKED_INDEPENDENT_ATTESTATION_INVALID",
                verify(s, s.signedCheckpoint(), s.proof(), s.pinnedLogKey(),
                    CHECKPOINT.minusSeconds(1),
                    (l, k, r, p) -> false).state());
        assertEquals("BLOCKED_CHECKPOINT_TIME_WINDOW",
                verify(s, s.signedCheckpoint(), s.proof(), s.pinnedLogKey(),
                    CHECKPOINT.plusSeconds(3600),
                    (l, k, r, p) -> false).state());
        // A signed checkpoint created after its referencing attestation is
        // a causal/time inconsistency even if both signatures are valid.
        var original = s.signedCheckpoint().checkpoint();
        var late = new ExternalCeremonyCheckpointVerifier.Checkpoint(
                original.logId(), original.verifierKeyId(), original.treeSize(),
                original.rootSha256(), ATTESTED.plusSeconds(30),
                ATTESTED.plusSeconds(3630));
        var signedLate = signCheckpoint(s.log(), late);
        var attestationWithLateHash = signAttestation(
                s.attestor(), attestation(digestHex(
                    ExternalCeremonyCheckpointVerifier.canonicalCheckpointBytes(late))));
        assertEquals("BLOCKED_CHECKPOINT_TIME_WINDOW",
                ExternalCeremonyCheckpointVerifier.inspect(
                    attestationWithLateHash, s.attestor().getPublic(), expected(s),
                    (key, proposal) -> false, signedLate, s.proof(),
                    s.pinnedLogKey(), NOW, (l, k, r, p) -> false).state());
        var c = s.signedCheckpoint().checkpoint();
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalCeremonyCheckpointVerifier.Checkpoint(
                    c.logId(), c.verifierKeyId(), 0, c.rootSha256(),
                    c.checkpointAt(), c.validUntil()));
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalCeremonyCheckpointVerifier.Checkpoint(
                    c.logId(), c.verifierKeyId(), Long.MAX_VALUE,
                    c.rootSha256(), c.checkpointAt(), c.validUntil()));
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalCeremonyCheckpointVerifier.Checkpoint(
                    c.logId(), c.verifierKeyId(), 1, c.rootSha256(),
                    c.checkpointAt(), c.checkpointAt().plusSeconds(48*3600L + 1)));
    }

    @Test
    void anyExternalRevocationOrUnavailableTrustCheckBlocks() throws Exception {
        var s = scenario();
        assertEquals("BLOCKED_EXTERNAL_LOG_REVOKED",
                verify(s, s.signedCheckpoint(), s.proof(), s.pinnedLogKey(),
                    NOW, (l, k, r, p) -> true).state());
        assertEquals("BLOCKED_EXTERNAL_LOG_REVOCATION_UNAVAILABLE",
                verify(s, s.signedCheckpoint(), s.proof(), s.pinnedLogKey(),
                    NOW, (l, k, r, p) -> { throw new IllegalStateException(); })
                    .state());
        assertEquals("BLOCKED_MISSING_LOG_OR_ATTESTATION_TRUST",
                verify(s, s.signedCheckpoint(), s.proof(), s.pinnedLogKey(),
                    NOW, null).state());
        var attesterRevoked = ExternalCeremonyCheckpointVerifier.inspect(
                s.signedAttestation(), s.attestor().getPublic(), expected(s),
                (k, p) -> true, s.signedCheckpoint(), s.proof(),
                s.pinnedLogKey(), NOW, (l, k, r, p) -> false);
        assertEquals("BLOCKED_INDEPENDENT_ATTESTATION_INVALID",
                attesterRevoked.state());
    }

    @Test
    void forgedOperationalPermissionsAreImpossibleEvenAfterInclusion()
            throws Exception {
        var s = scenario();
        var p = s.packet().proposalId();
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalCeremonyCheckpointVerifier.Evidence(
                    p, "CRYPTOGRAPHIC_INCLUSION_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                    true, true, false, false, false, false, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalCeremonyCheckpointVerifier.Evidence(
                    p, "CRYPTOGRAPHIC_INCLUSION_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                    true, false, true, false, false, false, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalCeremonyCheckpointVerifier.Evidence(
                    p, "CRYPTOGRAPHIC_INCLUSION_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                    true, false, false, true, false, false, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalCeremonyCheckpointVerifier.Evidence(
                    p, "CRYPTOGRAPHIC_INCLUSION_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                    true, false, false, false, true, false, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalCeremonyCheckpointVerifier.Evidence(
                    p, "CRYPTOGRAPHIC_INCLUSION_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                    false, false, false, false, false, false, true, false));
    }
}
