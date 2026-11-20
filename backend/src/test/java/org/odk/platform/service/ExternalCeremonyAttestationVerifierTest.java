package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TEST-ONLY ephemeral Ed25519 signing; NO external production audit private
 * key, verifier configuration, key enrollment or operational transfer.
 */
class ExternalCeremonyAttestationVerifierTest {
    private static final Instant WITNESSED_AT =
            Instant.parse("2026-11-20T11:59:31.123456Z");
    private static final Instant ATTESTED_AT =
            Instant.parse("2026-11-20T12:00:00Z");
    private static final Instant NOW =
            Instant.parse("2026-11-20T12:05:00Z");

    private KeyPair ephemeral() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private TypedMappingOfflineCeremonyService.CeremonyPacket packet() {
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
                "offline-checkpoint-001", "e".repeat(64), WITNESSED_AT,
                "EVIDENCE_COMMITTED_PENDING_EXTERNAL_ATTESTATION_NO_TRUST",
                false, false, false, false, false);
    }

    private ExternalCeremonyAttestationVerifier.Attestation attestation() {
        var packet = packet();
        return new ExternalCeremonyAttestationVerifier.Attestation(
                packet.projectId(), packet.proposalId(),
                packet.proposerId(), packet.reviewerId(), packet.witnessId(),
                UUID.fromString("faf5596e-526a-41d8-8f3c-453e4d36ccda"),
                packet.proposedKeyId(), packet.observedKeySha256(),
                packet.proposalEvidenceSha256(), packet.reviewEvidenceSha256(),
                packet.externalBundleSha256(), packet.externalReference(),
                packet.commitmentSha256(), "independent-external-audit-key-01",
                "f".repeat(64), packet.recordedAt(), ATTESTED_AT,
                ATTESTED_AT.plusSeconds(86400));
    }

    private ExternalCeremonyAttestationVerifier.Attestation copy(
            ExternalCeremonyAttestationVerifier.Attestation a,
            String bundleHash, String checkpointHash, UUID externalAttestor,
            Instant validUntil) {
        return new ExternalCeremonyAttestationVerifier.Attestation(
                a.projectId(), a.proposalId(), a.proposerId(), a.reviewerId(),
                a.witnessId(), externalAttestor, a.proposedKeyId(),
                a.proposedKeySha256(), a.proposalEvidenceSha256(),
                a.reviewEvidenceSha256(), bundleHash, a.externalReference(),
                a.commitmentSha256(), a.externalVerifierKeyId(),
                checkpointHash, a.witnessedAt(), a.attestedAt(), validUntil);
    }

    private ExternalCeremonyAttestationVerifier.SignedAttestation sign(
            ExternalCeremonyAttestationVerifier.Attestation a,
            KeyPair keys) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate());
        signature.update(
                ExternalCeremonyAttestationVerifier.canonicalBytes(a));
        return new ExternalCeremonyAttestationVerifier.SignedAttestation(
                a, Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(signature.sign()));
    }

    private ExternalCeremonyAttestationVerifier.ExpectedCeremony expected() {
        return new ExternalCeremonyAttestationVerifier.ExpectedCeremony(
                packet(), "independent-external-audit-key-01");
    }

    @Test
    void correctSignatureAndExactV7PacketAreEvidenceOnlyNotTrust()
            throws Exception {
        var keys = ephemeral();
        var a = attestation();
        var signed = sign(a, keys);
        var evidence = ExternalCeremonyAttestationVerifier.inspect(
                signed, keys.getPublic(), expected(), NOW,
                (key, proposal) -> false);
        assertEquals(a.proposalId(), evidence.proposalId());
        assertEquals(
                "SIGNATURE_VALID_ONLY_EXTERNAL_CHECKPOINT_UNVERIFIED_NO_TRUST",
                evidence.state());
        assertFalse(evidence.independentlyAnchored());
        assertFalse(evidence.trustedKeyRegistered());
        assertFalse(evidence.canIssueGrant());
        assertFalse(evidence.canReadHistoricalAnswers());
        assertFalse(evidence.canTransfer());
        assertFalse(evidence.canSubmit());
        assertArrayEquals(
                ExternalCeremonyAttestationVerifier.canonicalBytes(a),
                ExternalCeremonyAttestationVerifier.canonicalBytes(a));
    }

    @Test
    void externalVerifierKeyMustBeTrustedIndependentlyNotProposedKey()
            throws Exception {
        var actual = ephemeral();
        var wrong = ephemeral();
        var signed = sign(attestation(), actual);
        assertEquals("BLOCKED_BAD_EXTERNAL_SIGNATURE",
            ExternalCeremonyAttestationVerifier.inspect(
                signed, wrong.getPublic(), expected(), NOW,
                (k, p) -> false).state());
        assertEquals("BLOCKED_MISSING_EXTERNAL_TRUST_DEPENDENCY",
            ExternalCeremonyAttestationVerifier.inspect(
                signed, null, expected(), NOW,
                (k, p) -> false).state());
        assertEquals("BLOCKED_MISSING_EXTERNAL_TRUST_DEPENDENCY",
            ExternalCeremonyAttestationVerifier.inspect(
                signed, actual.getPublic(), null, NOW,
                (k, p) -> false).state());
    }

    @Test
    void tamperedBundleHashOrCheckpointInvalidatesRealSignature()
            throws Exception {
        var keys = ephemeral();
        var a = attestation();
        var signed = sign(a, keys);
        var alteredBundle = copy(a, "0".repeat(64),
                a.externalCheckpointSha256(),
                a.externalAttestorId(), a.expiresAt());
        var modified = new ExternalCeremonyAttestationVerifier.SignedAttestation(
                alteredBundle, signed.signatureBase64Url());
        assertEquals("BLOCKED_BAD_EXTERNAL_SIGNATURE",
            ExternalCeremonyAttestationVerifier.inspect(
                modified, keys.getPublic(), expected(), NOW,
                (k, p) -> false).state());

        var alteredAnchor = copy(a, a.externalBundleSha256(),
                "0".repeat(64), a.externalAttestorId(), a.expiresAt());
        assertEquals("BLOCKED_BAD_EXTERNAL_SIGNATURE",
            ExternalCeremonyAttestationVerifier.inspect(
                new ExternalCeremonyAttestationVerifier.SignedAttestation(
                    alteredAnchor, signed.signatureBase64Url()),
                keys.getPublic(), expected(), NOW, (k, p) -> false).state());
    }

    @Test
    void realSignatureForDifferentServerEvidenceIsNotTransferable()
            throws Exception {
        var keys = ephemeral();
        var a = attestation();
        var alteredBundle = copy(a, "0".repeat(64),
                a.externalCheckpointSha256(),
                a.externalAttestorId(), a.expiresAt());
        assertEquals("BLOCKED_CEREMONY_EVIDENCE_OR_KEY_ID_DRIFT",
            ExternalCeremonyAttestationVerifier.inspect(
                sign(alteredBundle, keys), keys.getPublic(), expected(),
                NOW, (k, p) -> false).state());

        var keyMismatch = new ExternalCeremonyAttestationVerifier.ExpectedCeremony(
            packet(), "independent-external-audit-key-02");
        assertEquals("BLOCKED_CEREMONY_EVIDENCE_OR_KEY_ID_DRIFT",
            ExternalCeremonyAttestationVerifier.inspect(
                sign(a, keys), keys.getPublic(), keyMismatch,
                NOW, (k, p) -> false).state());
    }

    @Test
    void genuineSignatureWithExpiredOrNotYetValidTimeIsRejected()
            throws Exception {
        var keys = ephemeral();
        var a = attestation();
        assertEquals("BLOCKED_EXTERNAL_ATTESTATION_NOT_CURRENT",
            ExternalCeremonyAttestationVerifier.inspect(
                sign(a, keys), keys.getPublic(), expected(),
                ATTESTED_AT.minusSeconds(1), (k, p) -> false).state());
        assertEquals("BLOCKED_EXTERNAL_ATTESTATION_NOT_CURRENT",
            ExternalCeremonyAttestationVerifier.inspect(
                sign(a, keys), keys.getPublic(), expected(),
                a.expiresAt(), (k, p) -> false).state());
        assertThrows(IllegalArgumentException.class, () ->
            copy(a, a.externalBundleSha256(), a.externalCheckpointSha256(),
                a.externalAttestorId(), ATTESTED_AT.plusSeconds(7 * 86400L + 1)));
    }

    @Test
    void unavailableOrRevokedExternalVerifierCannotVerifyEvidence()
            throws Exception {
        var keys = ephemeral();
        var signed = sign(attestation(), keys);
        assertEquals("BLOCKED_EXTERNAL_VERIFIER_REVOKED",
            ExternalCeremonyAttestationVerifier.inspect(
                signed, keys.getPublic(), expected(), NOW,
                (k, p) -> true).state());
        assertEquals("BLOCKED_EXTERNAL_REVOCATION_UNAVAILABLE",
            ExternalCeremonyAttestationVerifier.inspect(
                signed, keys.getPublic(), expected(), NOW,
                (k, p) -> { throw new IllegalStateException("offline"); }).state());
        assertEquals("BLOCKED_MISSING_EXTERNAL_TRUST_DEPENDENCY",
            ExternalCeremonyAttestationVerifier.inspect(
                signed, keys.getPublic(), expected(), NOW,
                null).state());
    }

    @Test
    void attestorCannotBeAnyOfProposerReviewerOrWitness() {
        var a = attestation();
        for (UUID sameUser : new UUID[]{
                a.proposerId(), a.reviewerId(), a.witnessId()}) {
            assertThrows(IllegalArgumentException.class, () ->
                copy(a, a.externalBundleSha256(),
                    a.externalCheckpointSha256(), sameUser, a.expiresAt()));
        }
        assertThrows(IllegalArgumentException.class, () ->
            copy(a, "UPPERCASE".repeat(8), a.externalCheckpointSha256(),
                a.externalAttestorId(), a.expiresAt()));
        assertThrows(IllegalArgumentException.class, () ->
            copy(a, a.externalBundleSha256(),
                a.externalCheckpointSha256(), a.externalAttestorId(),
                ATTESTED_AT));
    }

    @Test
    void malformedSignatureAndForgedPositivePermissionsAreUnconstructable() {
        var a = attestation();
        assertThrows(IllegalArgumentException.class, () ->
            new ExternalCeremonyAttestationVerifier.SignedAttestation(
                a, "not-a-signature"));
        assertThrows(IllegalArgumentException.class, () ->
            new ExternalCeremonyAttestationVerifier.SignedAttestation(
                a, "!".repeat(86)));
        assertThrows(IllegalArgumentException.class, () ->
            new ExternalCeremonyAttestationVerifier.AttestationEvidence(
                a.proposalId(), "SIGNATURE_VALID", true,
                false, false, false, false, false));
        assertThrows(IllegalArgumentException.class, () ->
            new ExternalCeremonyAttestationVerifier.AttestationEvidence(
                a.proposalId(), "SIGNATURE_VALID", false,
                true, false, false, false, false));
        assertThrows(IllegalArgumentException.class, () ->
            new ExternalCeremonyAttestationVerifier.AttestationEvidence(
                a.proposalId(), "SIGNATURE_VALID", false,
                false, false, true, false, false));
        assertThrows(IllegalArgumentException.class, () ->
            new ExternalCeremonyAttestationVerifier.AttestationEvidence(
                a.proposalId(), "SIGNATURE_VALID", false,
                false, false, false, true, false));
        assertThrows(IllegalArgumentException.class, () ->
            new ExternalCeremonyAttestationVerifier.AttestationEvidence(
                a.proposalId(), "SIGNATURE_VALID", false,
                false, false, false, false, true));
    }
}
