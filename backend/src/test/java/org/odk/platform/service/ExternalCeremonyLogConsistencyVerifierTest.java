package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Ephemeral keys and independently built Merkle proof fixtures ONLY. */
class ExternalCeremonyLogConsistencyVerifierTest {
    private static final Instant WITNESSED =
            Instant.parse("2026-11-20T11:59:31.123456Z");
    private static final Instant OLD_AT =
            Instant.parse("2026-11-20T11:50:00Z");
    private static final Instant NEW_AT =
            Instant.parse("2026-11-20T12:00:00Z");
    private static final Instant ATTESTED =
            Instant.parse("2026-11-20T12:01:00Z");
    private static final Instant NOW =
            Instant.parse("2026-11-20T12:05:00Z");
    private static final String LOG = "synthetic-external-ceremony-log";
    private static final String LOG_KEY = "separate-external-log-key-v1";
    private static final String ATTESTOR_KEY = "separate-external-attestor-v1";

    private record Scenario(
            KeyPair attestor, KeyPair log,
            ExternalCeremonyLogConsistencyVerifier.CurrentInclusion current,
            ExternalCeremonyLogConsistencyVerifier.HistoricalAnchor historical,
            ExternalCeremonyLogConsistencyVerifier.ConsistencyProof consistency) {}

    private static KeyPair ephemeral() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    private static String sign(KeyPair keys, byte[] bytes) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(bytes);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signer.sign());
    }

    private static byte[] syntheticLeaf(int i) throws Exception {
        byte[] payload = ("unrelated-leaf-" + i)
                .getBytes(StandardCharsets.UTF_8);
        byte[] separated = new byte[payload.length + 1];
        System.arraycopy(payload, 0, separated, 1, payload.length);
        return sha256(separated);
    }

    private static int k(int length) {
        return Integer.highestOneBit(length - 1);
    }

    private static byte[] treeRoot(List<byte[]> leaves) {
        int count = leaves.size();
        if (count == 1) return leaves.get(0);
        int part = k(count);
        return ExternalCeremonyCheckpointVerifier.nodeHash(
                treeRoot(leaves.subList(0, part)),
                treeRoot(leaves.subList(part, count)));
    }

    private static List<byte[]> inclusionPath(
            int index, List<byte[]> leaves) {
        if (leaves.size() == 1) return List.of();
        int part = k(leaves.size());
        List<byte[]> result = new ArrayList<>();
        if (index < part) {
            result.addAll(inclusionPath(index, leaves.subList(0, part)));
            result.add(treeRoot(leaves.subList(part, leaves.size())));
        } else {
            result.addAll(inclusionPath(
                    index - part, leaves.subList(part, leaves.size())));
            result.add(treeRoot(leaves.subList(0, part)));
        }
        return result;
    }

    /**
     * RFC6962 SUBPROOF(m, D[n], b) generated independently from the
     * production verifier. This models irregular and power-of-two prefixes.
     */
    private static List<byte[]> consistencyPath(
            int prefix, List<byte[]> leaves, boolean entire) {
        if (prefix == leaves.size()) {
            return entire ? List.of() : List.of(treeRoot(leaves));
        }
        int part = k(leaves.size());
        List<byte[]> result = new ArrayList<>();
        if (prefix <= part) {
            result.addAll(consistencyPath(
                    prefix, leaves.subList(0, part), entire));
            result.add(treeRoot(leaves.subList(part, leaves.size())));
        } else {
            result.addAll(consistencyPath(
                    prefix - part, leaves.subList(part, leaves.size()), false));
            result.add(treeRoot(leaves.subList(0, part)));
        }
        return result;
    }

    private static List<String> toHex(List<byte[]> bytes) {
        return bytes.stream().map(ExternalCeremonyLogConsistencyVerifierTest::hex)
                .toList();
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
                p.commitmentSha256(), ATTESTOR_KEY, checkpointHash,
                p.recordedAt(), ATTESTED, ATTESTED.plusSeconds(86400));
    }

    private static ExternalCeremonyCheckpointVerifier.SignedCheckpoint signHead(
            KeyPair log, ExternalCeremonyCheckpointVerifier.Checkpoint checkpoint)
            throws Exception {
        return new ExternalCeremonyCheckpointVerifier.SignedCheckpoint(
                checkpoint, sign(log,
                ExternalCeremonyCheckpointVerifier.canonicalCheckpointBytes(checkpoint)));
    }

    private static Scenario scenario(int oldSize, int newSize) throws Exception {
        if (oldSize < 1 || oldSize >= newSize || newSize < 2)
            throw new IllegalArgumentException("Strict prefix required");
        KeyPair attestor = ephemeral();
        KeyPair log = ephemeral();
        // The V7 commitment is at index 1, so the new tree has >=2 leaves.
        var preliminary = attestation("0".repeat(64));
        List<byte[]> leaves = new ArrayList<>();
        for (int i = 0; i < newSize; i++) {
            leaves.add(i == 1
                ? ExternalCeremonyCheckpointVerifier.leafHash(preliminary)
                : syntheticLeaf(i));
        }
        // For oldSize == 1 the committed V7 event is not in old history;
        // for oldSize > 1 it is. Both are legitimate append-only histories.
        var oldHead = new ExternalCeremonyCheckpointVerifier.Checkpoint(
                LOG, LOG_KEY, oldSize,
                hex(treeRoot(leaves.subList(0, oldSize))),
                OLD_AT, OLD_AT.plusSeconds(3600));
        var newerHead = new ExternalCeremonyCheckpointVerifier.Checkpoint(
                LOG, LOG_KEY, newSize, hex(treeRoot(leaves)),
                NEW_AT, NEW_AT.plusSeconds(3600));
        var newerSigned = signHead(log, newerHead);
        var olderSigned = signHead(log, oldHead);
        String newerDigest = hex(sha256(
                ExternalCeremonyCheckpointVerifier.canonicalCheckpointBytes(newerHead)));
        var signedAttestation = new ExternalCeremonyAttestationVerifier.SignedAttestation(
                attestation(newerDigest), sign(attestor,
                ExternalCeremonyAttestationVerifier.canonicalBytes(
                    attestation(newerDigest))));
        var inclusion = new ExternalCeremonyCheckpointVerifier.InclusionProof(
                1, toHex(inclusionPath(1, leaves)));
        var pinnedLog = new ExternalCeremonyCheckpointVerifier.TrustedLogKey(
                LOG, LOG_KEY, log.getPublic(),
                hex(sha256(log.getPublic().getEncoded())));
        var current = new ExternalCeremonyLogConsistencyVerifier.CurrentInclusion(
                signedAttestation, attestor.getPublic(),
                new ExternalCeremonyAttestationVerifier.ExpectedCeremony(
                    packet(), ATTESTOR_KEY),
                (key, proposal) -> false, newerSigned, inclusion,
                pinnedLog, (logId, keyId, root, proposal) -> false);
        var historical = new ExternalCeremonyLogConsistencyVerifier.HistoricalAnchor(
                olderSigned, hex(sha256(
                    ExternalCeremonyCheckpointVerifier.canonicalCheckpointBytes(oldHead))));
        var consistency = new ExternalCeremonyLogConsistencyVerifier.ConsistencyProof(
                toHex(consistencyPath(oldSize, leaves, true)));
        return new Scenario(attestor, log, current, historical, consistency);
    }

    private static ExternalCeremonyLogConsistencyVerifier.ConsistencyEvidence
            verify(Scenario s) {
        return ExternalCeremonyLogConsistencyVerifier.inspect(
                s.current(), s.historical(), s.consistency(), NOW);
    }

    @Test
    void signedThreeToFiveTreeConsistencyIsOnlyCryptographicEvidence()
            throws Exception {
        var s = scenario(3, 5);
        var proof = verify(s);
        assertEquals(
                "CRYPTOGRAPHIC_APPEND_ONLY_PROOF_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                proof.state());
        assertTrue(proof.cryptographicConsistencyVerified());
        assertFalse(proof.independentlyPublished());
        assertFalse(proof.antiSplitViewVerified());
        assertFalse(proof.trustedKeyRegistered());
        assertFalse(proof.canIssueGrant());
        assertFalse(proof.canReadHistoricalAnswers());
        assertFalse(proof.canTransfer());
        assertFalse(proof.canSubmit());
        assertEquals(packet().proposalId(), proof.proposalId());
    }

    @Test
    void rfc6962ConsistencyWorksExhaustivelyForAllPrefixesUpToFortyLeaves()
            throws Exception {
        List<byte[]> all = new ArrayList<>();
        for (int i = 0; i < 40; i++) all.add(syntheticLeaf(i));
        for (int n = 2; n <= 40; n++) {
            List<byte[]> tree = all.subList(0, n);
            byte[] newestRoot = treeRoot(tree);
            for (int m = 1; m < n; m++) {
                byte[] oldRoot = treeRoot(tree.subList(0, m));
                var proof = new ExternalCeremonyLogConsistencyVerifier.ConsistencyProof(
                        toHex(consistencyPath(m, tree, true)));
                assertTrue(ExternalCeremonyLogConsistencyVerifier
                        .verifyConsistency(m, n, oldRoot, newestRoot, proof),
                        "RFC6962 prefix " + m + " of " + n);
                if (!proof.siblingsSha256().isEmpty()) {
                    List<String> altered = new ArrayList<>(proof.siblingsSha256());
                    altered.set(0, "f".repeat(64));
                    assertFalse(ExternalCeremonyLogConsistencyVerifier
                        .verifyConsistency(m, n, oldRoot, newestRoot,
                            new ExternalCeremonyLogConsistencyVerifier.ConsistencyProof(
                                altered)), "Tampered RFC6962 " + m + "/" + n);
                }
            }
        }
    }

    @Test
    void missingExtraOrSwappedProofNodesFailClosed() throws Exception {
        var s = scenario(3, 5);
        List<String> path = s.consistency().siblingsSha256();
        assertTrue(path.size() >= 2);
        List<String> truncated = path.subList(0, path.size() - 1);
        List<String> extra = new ArrayList<>(path);
        extra.add("f".repeat(64));
        List<String> swapped = new ArrayList<>(path);
        java.util.Collections.swap(swapped, 0, 1);
        for (List<String> candidate : List.of(truncated, extra, swapped)) {
            var result = ExternalCeremonyLogConsistencyVerifier.inspect(
                s.current(), s.historical(),
                new ExternalCeremonyLogConsistencyVerifier.ConsistencyProof(candidate),
                NOW);
            assertEquals("BLOCKED_LOG_CONSISTENCY_PROOF", result.state());
            assertFalse(result.cryptographicConsistencyVerified());
        }
        assertThrows(IllegalArgumentException.class, () ->
            new ExternalCeremonyLogConsistencyVerifier.ConsistencyProof(
                java.util.Collections.nCopies(65, "0".repeat(64))));
        assertThrows(IllegalArgumentException.class, () ->
            new ExternalCeremonyLogConsistencyVerifier.ConsistencyProof(
                List.of("bad-digest")));
    }

    @Test
    void externallyPinnedOldHeadDigestAndSignatureMustBothMatch() throws Exception {
        var s = scenario(4, 8);
        assertTrue(verify(s).cryptographicConsistencyVerified());
        var forgedPin = new ExternalCeremonyLogConsistencyVerifier.HistoricalAnchor(
                s.historical().prior(), "0".repeat(64));
        assertEquals("BLOCKED_HISTORICAL_CHECKPOINT_PIN_MISMATCH",
            ExternalCeremonyLogConsistencyVerifier.inspect(
                s.current(), forgedPin, s.consistency(), NOW).state());
        var fake = ephemeral();
        var old = s.historical().prior().checkpoint();
        var badSignature = new ExternalCeremonyCheckpointVerifier.SignedCheckpoint(
                old, sign(fake,
                ExternalCeremonyCheckpointVerifier.canonicalCheckpointBytes(old)));
        var pinned = new ExternalCeremonyLogConsistencyVerifier.HistoricalAnchor(
                badSignature, s.historical().externallyPinnedCheckpointSha256());
        assertEquals("BLOCKED_HISTORICAL_LOG_SIGNATURE",
            ExternalCeremonyLogConsistencyVerifier.inspect(
                s.current(), pinned, s.consistency(), NOW).state());
    }

    @Test
    void oldEqualOrGreaterTreeCannotBeReplayedAsFreshProgress()
            throws Exception {
        var s = scenario(3, 5);
        var newer = s.current().newer();
        var same = new ExternalCeremonyLogConsistencyVerifier.HistoricalAnchor(
            newer, hex(sha256(
                ExternalCeremonyCheckpointVerifier.canonicalCheckpointBytes(
                    newer.checkpoint()))));
        assertEquals("BLOCKED_ROLLBACK_OR_NO_TREE_PROGRESS",
            ExternalCeremonyLogConsistencyVerifier.inspect(
                s.current(), same, s.consistency(), NOW).state());
        var mismatchedOld = new ExternalCeremonyCheckpointVerifier.Checkpoint(
                LOG, LOG_KEY, 6,
                s.historical().prior().checkpoint().rootSha256(),
                OLD_AT, OLD_AT.plusSeconds(3600));
        var oldSigned = signHead(s.log(), mismatchedOld);
        var rolled = new ExternalCeremonyLogConsistencyVerifier.HistoricalAnchor(
                oldSigned, hex(sha256(
                    ExternalCeremonyCheckpointVerifier.canonicalCheckpointBytes(
                        mismatchedOld))));
        assertEquals("BLOCKED_ROLLBACK_OR_NO_TREE_PROGRESS",
            ExternalCeremonyLogConsistencyVerifier.inspect(
                s.current(), rolled, s.consistency(), NOW).state());
    }

    @Test
    void oldSignedForkCannotBePassedAsAppendOnlyHistory() throws Exception {
        var s = scenario(3, 5);
        var prior = s.historical().prior().checkpoint();
        var forked = new ExternalCeremonyCheckpointVerifier.Checkpoint(
                prior.logId(), prior.verifierKeyId(), prior.treeSize(),
                "0".repeat(64), prior.checkpointAt(), prior.validUntil());
        var fakeButSigned = signHead(s.log(), forked);
        var anchor = new ExternalCeremonyLogConsistencyVerifier.HistoricalAnchor(
                fakeButSigned,
                hex(sha256(
                    ExternalCeremonyCheckpointVerifier.canonicalCheckpointBytes(
                        forked))));
        assertEquals("BLOCKED_LOG_CONSISTENCY_PROOF",
            ExternalCeremonyLogConsistencyVerifier.inspect(
                s.current(), anchor, s.consistency(), NOW).state());
    }

    @Test
    void changedLogIdentityAndLateOldCheckpointAreDenied() throws Exception {
        var s = scenario(3, 5);
        var old = s.historical().prior().checkpoint();
        var differentLog = new ExternalCeremonyCheckpointVerifier.Checkpoint(
                "different-log", old.verifierKeyId(), old.treeSize(),
                old.rootSha256(), old.checkpointAt(), old.validUntil());
        var signed = signHead(s.log(), differentLog);
        var anchor = new ExternalCeremonyLogConsistencyVerifier.HistoricalAnchor(
                signed, hex(sha256(
                    ExternalCeremonyCheckpointVerifier.canonicalCheckpointBytes(
                        differentLog))));
        assertEquals("BLOCKED_HISTORICAL_LOG_ID_OR_KEY_DRIFT",
            ExternalCeremonyLogConsistencyVerifier.inspect(
                s.current(), anchor, s.consistency(), NOW).state());
        var futureOld = new ExternalCeremonyCheckpointVerifier.Checkpoint(
                old.logId(), old.verifierKeyId(), old.treeSize(),
                old.rootSha256(), NEW_AT.plusSeconds(3),
                NEW_AT.plusSeconds(3603));
        var signedFuture = signHead(s.log(), futureOld);
        var late = new ExternalCeremonyLogConsistencyVerifier.HistoricalAnchor(
                signedFuture, hex(sha256(
                    ExternalCeremonyCheckpointVerifier.canonicalCheckpointBytes(
                        futureOld))));
        assertEquals("BLOCKED_HISTORICAL_CHECKPOINT_TIME_DRIFT",
            ExternalCeremonyLogConsistencyVerifier.inspect(
                s.current(), late, s.consistency(), NOW).state());
    }

    @Test
    void revokedOrUnavailablePastLogStateDeniesEvenValidAppendOnlyProof()
            throws Exception {
        var s = scenario(3, 5);
        var original = s.current();
        var olderRoot = s.historical().prior().checkpoint().rootSha256();
        var selectiveRevoked = new ExternalCeremonyLogConsistencyVerifier.CurrentInclusion(
                original.attestation(), original.attestorKey(),
                original.currentPacket(), original.attestorRevocation(),
                original.newer(), original.inclusionProof(),
                original.pinnedLogKey(),
                (log, key, root, proposal) -> root.equals(olderRoot));
        assertEquals("BLOCKED_HISTORICAL_LOG_REVOKED",
            ExternalCeremonyLogConsistencyVerifier.inspect(
                selectiveRevoked, s.historical(), s.consistency(), NOW).state());
        var unavailable = new ExternalCeremonyLogConsistencyVerifier.CurrentInclusion(
                original.attestation(), original.attestorKey(),
                original.currentPacket(), original.attestorRevocation(),
                original.newer(), original.inclusionProof(),
                original.pinnedLogKey(),
                (log, key, root, proposal) -> {
                    if (root.equals(olderRoot))
                        throw new IllegalStateException("offline");
                    return false;
                });
        assertEquals("BLOCKED_HISTORICAL_LOG_REVOCATION_UNAVAILABLE",
            ExternalCeremonyLogConsistencyVerifier.inspect(
                unavailable, s.historical(), s.consistency(), NOW).state());
        assertEquals("BLOCKED_MISSING_HISTORICAL_TRUST_DEPENDENCY",
            ExternalCeremonyLogConsistencyVerifier.inspect(
                original, null, s.consistency(), NOW).state());
        var attestorRevoked = new ExternalCeremonyLogConsistencyVerifier.CurrentInclusion(
                original.attestation(), original.attestorKey(),
                original.currentPacket(), (key, proposal) -> true,
                original.newer(), original.inclusionProof(),
                original.pinnedLogKey(), original.logRevocation());
        assertEquals("BLOCKED_CURRENT_SIGNED_INCLUSION_INVALID",
            ExternalCeremonyLogConsistencyVerifier.inspect(
                attestorRevoked, s.historical(), s.consistency(), NOW).state());
    }

    @Test
    void evidenceCannotBeForgedToClaimPublicationOrAccess() {
        UUID proposal = packet().proposalId();
        assertThrows(IllegalArgumentException.class, () ->
            new ExternalCeremonyLogConsistencyVerifier.ConsistencyEvidence(
                proposal,
                "CRYPTOGRAPHIC_APPEND_ONLY_PROOF_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                true, true, false, false, false, false, false, false));
        assertThrows(IllegalArgumentException.class, () ->
            new ExternalCeremonyLogConsistencyVerifier.ConsistencyEvidence(
                proposal,
                "CRYPTOGRAPHIC_APPEND_ONLY_PROOF_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                true, false, true, false, false, false, false, false));
        assertThrows(IllegalArgumentException.class, () ->
            new ExternalCeremonyLogConsistencyVerifier.ConsistencyEvidence(
                proposal,
                "CRYPTOGRAPHIC_APPEND_ONLY_PROOF_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                true, false, false, true, false, false, false, false));
        assertThrows(IllegalArgumentException.class, () ->
            new ExternalCeremonyLogConsistencyVerifier.ConsistencyEvidence(
                proposal,
                "CRYPTOGRAPHIC_APPEND_ONLY_PROOF_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                false, false, false, false, false, false, true, false));
    }
}
