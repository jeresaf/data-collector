package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Only synthetic in-memory independent Ed25519 signing keys are used. */
class ExternalCheckpointWitnessQuorumVerifierTest {
    private static final Instant NOW =
            Instant.parse("2026-11-20T12:05:00Z");

    private record Scenario(
            ExternalCeremonyLogConsistencyVerifierTest.Scenario history,
            KeyPair witnessOne, KeyPair witnessTwo,
            ExternalCheckpointWitnessQuorumVerifier.WitnessProof first,
            ExternalCheckpointWitnessQuorumVerifier.WitnessProof second) {}

    private static KeyPair ephemeral() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
            ExternalCeremonyLogConsistencyVerifierTest.sha256(bytes));
    }

    private static String sig(KeyPair pair, byte[] bytes) throws Exception {
        return ExternalCeremonyLogConsistencyVerifierTest.sign(pair, bytes);
    }

    private static ExternalCheckpointWitnessQuorumVerifier.WitnessReceipt receipt(
            String witness, String keyId,
            ExternalCeremonyLogConsistencyVerifierTest.Scenario history)
            throws Exception {
        var checkpoint = history.current().newer().checkpoint();
        return new ExternalCheckpointWitnessQuorumVerifier.WitnessReceipt(
                witness, keyId, checkpoint.logId(), checkpoint.verifierKeyId(),
                checkpoint.treeSize(), checkpoint.rootSha256(),
                sha(ExternalCeremonyCheckpointVerifier
                    .canonicalCheckpointBytes(checkpoint)),
                checkpoint.checkpointAt(),
                checkpoint.checkpointAt().plusSeconds(90),
                checkpoint.checkpointAt().plusSeconds(3600));
    }

    private static ExternalCheckpointWitnessQuorumVerifier.WitnessProof
            signedReceipt(KeyPair key,
                          ExternalCheckpointWitnessQuorumVerifier.WitnessReceipt r)
            throws Exception {
        var signed = new ExternalCheckpointWitnessQuorumVerifier.SignedReceipt(
                r, sig(key, ExternalCheckpointWitnessQuorumVerifier
                        .canonicalBytes(r)));
        var trust = new ExternalCheckpointWitnessQuorumVerifier.TrustedWitness(
                r.witnessId(), r.witnessVerifierKeyId(), key.getPublic(),
                sha(key.getPublic().getEncoded()));
        return new ExternalCheckpointWitnessQuorumVerifier.WitnessProof(
                signed, trust);
    }

    private static Scenario scenario() throws Exception {
        var history = ExternalCeremonyLogConsistencyVerifierTest.scenario(3, 5);
        var one = ephemeral();
        var two = ephemeral();
        return new Scenario(history, one, two,
                signedReceipt(one, receipt(
                    "external-archive-operator-A", "archive-witness-key-A",
                    history)),
                signedReceipt(two, receipt(
                    "independent-public-monitor-B", "monitor-witness-key-B",
                    history)));
    }

    private static ExternalCheckpointWitnessQuorumVerifier.Evidence verify(
            Scenario s,
            ExternalCheckpointWitnessQuorumVerifier.WitnessProof a,
            ExternalCheckpointWitnessQuorumVerifier.WitnessProof b,
            Instant now,
            ExternalCheckpointWitnessQuorumVerifier.WitnessRevocationLookup veto) {
        return ExternalCheckpointWitnessQuorumVerifier.inspect(
                s.history().current(), s.history().historical(),
                s.history().consistency(), a, b, now, veto);
    }

    @Test
    void distinctSignedWitnessesAgreeButCannotProvePublicationOrActivate()
            throws Exception {
        var s = scenario();
        var evidence = verify(s, s.first(), s.second(), NOW,
                (witness, key, digest, proposal) -> false);
        assertEquals(
            "TWO_WITNESS_SIGNATURES_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
            evidence.state());
        assertTrue(evidence.cryptographicWitnessAgreement());
        assertFalse(evidence.independentlyPublished());
        assertFalse(evidence.antiSplitViewVerified());
        assertFalse(evidence.trustedKeyRegistered());
        assertFalse(evidence.canIssueGrant());
        assertFalse(evidence.canReadHistoricalAnswers());
        assertFalse(evidence.canTransfer());
        assertFalse(evidence.canSubmit());
        assertEquals(s.history().current().attestation()
                .attestation().proposalId(), evidence.proposalId());
        assertArrayEquals(
            ExternalCheckpointWitnessQuorumVerifier.canonicalBytes(
                s.first().signed().receipt()),
            ExternalCheckpointWitnessQuorumVerifier.canonicalBytes(
                s.first().signed().receipt()));
    }

    @Test
    void oneWitnessOrWrongSignatureAndWrongPublicPinFailClosed()
            throws Exception {
        var s = scenario();
        assertEquals("BLOCKED_MISSING_INDEPENDENT_WITNESS_TRUST",
            verify(s, s.first(), null, NOW,
                (witness, key, digest, proposal) -> false).state());
        var substituted = new ExternalCheckpointWitnessQuorumVerifier
                .WitnessProof(s.first().signed(), s.second().trusted());
        assertEquals("BLOCKED_UNTRUSTED_OR_SHARED_WITNESS_SIGNER",
            verify(s, substituted, s.second(), NOW,
                (witness, key, digest, proposal) -> false).state());

        var fakePublic = ephemeral();
        var forgedTrust = new ExternalCheckpointWitnessQuorumVerifier
                .TrustedWitness(s.first().trusted().witnessId(),
                s.first().trusted().verifierKeyId(),
                fakePublic.getPublic(), sha(fakePublic.getPublic().getEncoded()));
        var forged = new ExternalCheckpointWitnessQuorumVerifier.WitnessProof(
                s.first().signed(), forgedTrust);
        assertEquals("BLOCKED_BAD_EXTERNAL_WITNESS_SIGNATURE",
            verify(s, forged, s.second(), NOW,
                (witness, key, digest, proposal) -> false).state());

        var badPin = new ExternalCheckpointWitnessQuorumVerifier.TrustedWitness(
                s.first().trusted().witnessId(),
                s.first().trusted().verifierKeyId(),
                s.witnessOne().getPublic(), "f".repeat(64));
        assertEquals("BLOCKED_UNTRUSTED_OR_SHARED_WITNESS_SIGNER",
            verify(s, new ExternalCheckpointWitnessQuorumVerifier.WitnessProof(
                s.first().signed(), badPin), s.second(), NOW,
                (witness, key, digest, proposal) -> false).state());
    }

    @Test
    void twoValidSignedSameSizeDivergentRootsDetectEquivocation()
            throws Exception {
        var s = scenario();
        var old = s.second().signed().receipt();
        var changed = new ExternalCheckpointWitnessQuorumVerifier.WitnessReceipt(
                old.witnessId(), old.witnessVerifierKeyId(),
                old.logId(), old.logVerifierKeyId(), old.treeSize(),
                "f".repeat(64), old.canonicalCheckpointSha256(),
                old.checkpointAt(), old.observedAt(), old.expiresAt());
        var anotherSigned = signedReceipt(s.witnessTwo(), changed);
        assertEquals("BLOCKED_SIGNED_WITNESS_EQUIVOCATION",
            verify(s, s.first(), anotherSigned, NOW,
                (witness, key, digest, proposal) -> false).state());
    }

    @Test
    void signedWrongLogOrWrongTreeDoesNotFormAQuorum() throws Exception {
        var s = scenario();
        var base = s.second().signed().receipt();
        var otherLog = new ExternalCheckpointWitnessQuorumVerifier.WitnessReceipt(
                base.witnessId(), base.witnessVerifierKeyId(),
                "another-log", base.logVerifierKeyId(), base.treeSize(),
                base.rootSha256(), base.canonicalCheckpointSha256(),
                base.checkpointAt(), base.observedAt(), base.expiresAt());
        assertEquals("BLOCKED_SIGNED_WITNESS_HEAD_MISMATCH",
            verify(s, s.first(), signedReceipt(s.witnessTwo(), otherLog),
                NOW, (witness, key, digest, proposal) -> false).state());

        var otherSize = new ExternalCheckpointWitnessQuorumVerifier.WitnessReceipt(
                base.witnessId(), base.witnessVerifierKeyId(),
                base.logId(), base.logVerifierKeyId(), base.treeSize() + 1,
                base.rootSha256(), base.canonicalCheckpointSha256(),
                base.checkpointAt(), base.observedAt(), base.expiresAt());
        assertEquals("BLOCKED_SIGNED_WITNESS_HEAD_MISMATCH",
            verify(s, s.first(), signedReceipt(s.witnessTwo(), otherSize),
                NOW, (witness, key, digest, proposal) -> false).state());
    }

    @Test
    void duplicateSignerAccountOrSharedPublicKeyCannotBeTwoWitnesses()
            throws Exception {
        var s = scenario();
        assertEquals("BLOCKED_DUPLICATE_OR_SHARED_WITNESS_IDENTITY",
            verify(s, s.first(), s.first(), NOW,
                (witness, key, digest, proposal) -> false).state());
        var duplicateKey = receipt("another-monitor-with-same-key",
                "different-witness-key-id", s.history());
        var reused = signedReceipt(s.witnessOne(), duplicateKey);
        assertEquals("BLOCKED_DUPLICATE_OR_SHARED_WITNESS_IDENTITY",
            verify(s, s.first(), reused, NOW,
                (witness, key, digest, proposal) -> false).state());
        var reusedId = receipt(s.first().trusted().witnessId(),
                "different-key-id", s.history());
        assertEquals("BLOCKED_DUPLICATE_OR_SHARED_WITNESS_IDENTITY",
            verify(s, s.first(), signedReceipt(s.witnessTwo(), reusedId),
                NOW, (witness, key, digest, proposal) -> false).state());
    }

    @Test
    void logOrAttestorKeyCannotAlsoActAsIndependentWitness()
            throws Exception {
        var s = scenario();
        var sameLogKey = signedReceipt(s.history().log(), receipt(
                "log-operator", "log-signature-reused", s.history()));
        assertEquals("BLOCKED_UNTRUSTED_OR_SHARED_WITNESS_SIGNER",
            verify(s, sameLogKey, s.second(), NOW,
                (witness, key, digest, proposal) -> false).state());
        var sameAttestor = signedReceipt(s.history().attestor(), receipt(
                "attestor-reused", "attestor-signature-reused", s.history()));
        assertEquals("BLOCKED_UNTRUSTED_OR_SHARED_WITNESS_SIGNER",
            verify(s, sameAttestor, s.second(), NOW,
                (witness, key, digest, proposal) -> false).state());
    }

    @Test
    void witnessRevokedOrRevocationProviderMissingFailsClosed()
            throws Exception {
        var s = scenario();
        assertEquals("BLOCKED_EXTERNAL_WITNESS_REVOKED",
            verify(s, s.first(), s.second(), NOW,
                (witness, key, digest, proposal) ->
                    witness.equals("independent-public-monitor-B")).state());
        assertEquals("BLOCKED_EXTERNAL_WITNESS_REVOCATION_UNAVAILABLE",
            verify(s, s.first(), s.second(), NOW,
                (witness, key, digest, proposal) -> {
                    throw new IllegalStateException("unavailable");
                }).state());
        assertEquals("BLOCKED_MISSING_INDEPENDENT_WITNESS_TRUST",
            verify(s, s.first(), s.second(), NOW, null).state());
    }

    @Test
    void futureAndExpiredReceiptsCannotBeReplayed() throws Exception {
        var s = scenario();
        var first = s.first().signed().receipt();
        var future = new ExternalCheckpointWitnessQuorumVerifier.WitnessReceipt(
                first.witnessId(), first.witnessVerifierKeyId(),
                first.logId(), first.logVerifierKeyId(), first.treeSize(),
                first.rootSha256(), first.canonicalCheckpointSha256(),
                first.checkpointAt(), NOW.plusSeconds(60),
                NOW.plusSeconds(600));
        assertEquals("BLOCKED_EXPIRED_OR_FUTURE_WITNESS_RECEIPT",
            verify(s, signedReceipt(s.witnessOne(), future), s.second(),
                NOW, (w, k, d, p) -> false).state());

        var expired = new ExternalCheckpointWitnessQuorumVerifier.WitnessReceipt(
                first.witnessId(), first.witnessVerifierKeyId(),
                first.logId(), first.logVerifierKeyId(), first.treeSize(),
                first.rootSha256(), first.canonicalCheckpointSha256(),
                first.checkpointAt(), first.observedAt(),
                first.observedAt().plusSeconds(1));
        assertEquals("BLOCKED_EXPIRED_OR_FUTURE_WITNESS_RECEIPT",
            verify(s, signedReceipt(s.witnessOne(), expired), s.second(),
                NOW, (w, k, d, p) -> false).state());
    }

    @Test
    void cannotInventApprovalOrUnboundedWitnessReceipt() throws Exception {
        var s = scenario();
        var r = s.first().signed().receipt();
        assertThrows(IllegalArgumentException.class, () ->
                new ExternalCheckpointWitnessQuorumVerifier.WitnessReceipt(
                    r.witnessId(), r.witnessVerifierKeyId(), r.logId(),
                    r.logVerifierKeyId(), r.treeSize(), r.rootSha256(),
                    r.canonicalCheckpointSha256(), r.checkpointAt(),
                    r.checkpointAt().plusSeconds(6 * 3600 + 1),
                    r.checkpointAt().plusSeconds(7 * 3600 + 1)));
        assertThrows(IllegalArgumentException.class, () ->
                new ExternalCheckpointWitnessQuorumVerifier.WitnessReceipt(
                    r.witnessId(), r.witnessVerifierKeyId(), r.logId(),
                    r.logVerifierKeyId(), r.treeSize(), r.rootSha256(),
                    r.canonicalCheckpointSha256(), r.checkpointAt(),
                    r.observedAt(), r.observedAt().plusSeconds(86401)));
        assertThrows(IllegalArgumentException.class, () ->
                new ExternalCheckpointWitnessQuorumVerifier.SignedReceipt(
                    r, "not-a-signature"));
        assertThrows(IllegalArgumentException.class, () ->
                new ExternalCheckpointWitnessQuorumVerifier.Evidence(
                    s.history().current().attestation().attestation().proposalId(),
                    "TWO_WITNESS_SIGNATURES_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                    true, true, false, false, false, false, false, false));
        assertThrows(IllegalArgumentException.class, () ->
                new ExternalCheckpointWitnessQuorumVerifier.Evidence(
                    s.history().current().attestation().attestation().proposalId(),
                    "TWO_WITNESS_SIGNATURES_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                    true, false, true, false, false, false, false, false));
        assertThrows(IllegalArgumentException.class, () ->
                new ExternalCheckpointWitnessQuorumVerifier.Evidence(
                    s.history().current().attestation().attestation().proposalId(),
                    "TWO_WITNESS_SIGNATURES_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                    true, false, false, true, false, false, false, false));
        assertThrows(IllegalArgumentException.class, () ->
                new ExternalCheckpointWitnessQuorumVerifier.Evidence(
                    s.history().current().attestation().attestation().proposalId(),
                    "TWO_WITNESS_SIGNATURES_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                    true, false, false, false, false, true, false, false));
        assertThrows(IllegalArgumentException.class, () ->
                new ExternalCheckpointWitnessQuorumVerifier.Evidence(
                    s.history().current().attestation().attestation().proposalId(),
                    "TWO_WITNESS_SIGNATURES_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                    true, false, false, false, false, false, false, true));
    }
}
