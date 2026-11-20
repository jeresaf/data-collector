package org.odk.platform.service;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M3D4C2B2B2C1: read-only proof of append-only Merkle-tree consistency
 * between a previously externally pinned signed checkpoint and the newer
 * checkpoint that contains the current V7 ceremony commitment.
 *
 * A caller-supplied prior "pin" cannot prove independent publication.
 * There is intentionally NO production external fetcher, pinned catalog,
 * gossip/anti-split-view monitor, activation, private key or HTTP endpoint.
 * Even success cannot authorize a signing key, grant or answer transfer.
 */
public final class ExternalCeremonyLogConsistencyVerifier {
    private ExternalCeremonyLogConsistencyVerifier() {}

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * Caller MUST obtain this historical checkpoint digest from an
     * independently governed witness/publisher outside the application DB.
     * This class checks equality and signature only; it does not establish
     * that the supplied pin is genuinely external.
     */
    public record HistoricalAnchor(
            ExternalCeremonyCheckpointVerifier.SignedCheckpoint prior,
            String externallyPinnedCheckpointSha256) {
        public HistoricalAnchor {
            if (prior == null || !validHash(externallyPinnedCheckpointSha256)) {
                throw new IllegalArgumentException(
                        "A complete signed historical checkpoint and pin are required");
            }
        }
    }

    /** RFC6962 consistency audit path, bounded before cryptographic work. */
    public record ConsistencyProof(List<String> siblingsSha256) {
        public ConsistencyProof {
            if (siblingsSha256 == null || siblingsSha256.size() > 64 ||
                    siblingsSha256.stream().anyMatch(s -> !validHash(s))) {
                throw new IllegalArgumentException(
                        "A bounded canonical consistency audit path is required");
            }
            siblingsSha256 = List.copyOf(siblingsSha256);
        }
    }

    /** These supplied dependencies are *not* provisioned as a production trust root. */
    public record CurrentInclusion(
            ExternalCeremonyAttestationVerifier.SignedAttestation attestation,
            java.security.PublicKey attestorKey,
            ExternalCeremonyAttestationVerifier.ExpectedCeremony currentPacket,
            ExternalCeremonyAttestationVerifier.RevocationLookup attestorRevocation,
            ExternalCeremonyCheckpointVerifier.SignedCheckpoint newer,
            ExternalCeremonyCheckpointVerifier.InclusionProof inclusionProof,
            ExternalCeremonyCheckpointVerifier.TrustedLogKey pinnedLogKey,
            ExternalCeremonyCheckpointVerifier.LogRevocationLookup logRevocation) { }

    /**
     * Evidence of mathematical consistency, NOT independent publication,
     * externally attested immutable history, or operational transfer rights.
     */
    public record ConsistencyEvidence(
            UUID proposalId, String state, boolean cryptographicConsistencyVerified,
            boolean independentlyPublished, boolean antiSplitViewVerified,
            boolean trustedKeyRegistered, boolean canIssueGrant,
            boolean canReadHistoricalAnswers, boolean canTransfer,
            boolean canSubmit) {
        public ConsistencyEvidence {
            if (independentlyPublished || antiSplitViewVerified ||
                    trustedKeyRegistered || canIssueGrant ||
                    canReadHistoricalAnswers || canTransfer || canSubmit ||
                    (cryptographicConsistencyVerified &&
                     !"CRYPTOGRAPHIC_APPEND_ONLY_PROOF_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST"
                            .equals(state))) {
                throw new IllegalArgumentException(
                        "Merkle consistency evidence cannot confer trust or data rights");
            }
        }
    }

    public static ConsistencyEvidence inspect(
            CurrentInclusion current, HistoricalAnchor prior,
            ConsistencyProof proof, Instant serverNow) {
        UUID proposal = current == null || current.attestation() == null ?
                null : current.attestation().attestation().proposalId();
        if (current == null || prior == null || proof == null ||
                serverNow == null || current.newer() == null ||
                current.pinnedLogKey() == null ||
                current.logRevocation() == null) {
            return blocked(proposal, "BLOCKED_MISSING_HISTORICAL_TRUST_DEPENDENCY");
        }

        // The newer signed checkpoint must also contain the exact V7 leaf
        // under PR19, with the separately verified PR18 attestation.
        var newerEvidence = ExternalCeremonyCheckpointVerifier.inspect(
                current.attestation(), current.attestorKey(),
                current.currentPacket(), current.attestorRevocation(),
                current.newer(), current.inclusionProof(),
                current.pinnedLogKey(), serverNow, current.logRevocation());
        if (!"CRYPTOGRAPHIC_INCLUSION_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST"
                .equals(newerEvidence.state())) {
            return blocked(proposal, "BLOCKED_CURRENT_SIGNED_INCLUSION_INVALID");
        }
        var old = prior.prior().checkpoint();
        var newer = current.newer().checkpoint();
        if (!Objects.equals(old.logId(), newer.logId()) ||
                !Objects.equals(old.verifierKeyId(), newer.verifierKeyId()) ||
                !Objects.equals(old.logId(), current.pinnedLogKey().logId()) ||
                !Objects.equals(old.verifierKeyId(), current.pinnedLogKey().keyId())) {
            return blocked(proposal, "BLOCKED_HISTORICAL_LOG_ID_OR_KEY_DRIFT");
        }
        if (old.treeSize() >= newer.treeSize()) {
            // An equal head is not fresh append-only progress; a smaller
            // head is a rollback. Different equal-size roots are equivocation.
            return blocked(proposal, "BLOCKED_ROLLBACK_OR_NO_TREE_PROGRESS");
        }
        if (!old.checkpointAt().isBefore(newer.checkpointAt())) {
            return blocked(proposal, "BLOCKED_HISTORICAL_CHECKPOINT_TIME_DRIFT");
        }

        byte[] priorBytes = ExternalCeremonyCheckpointVerifier
                .canonicalCheckpointBytes(old);
        if (!MessageDigest.isEqual(
                digest(priorBytes),
                HexFormat.of().parseHex(prior.externallyPinnedCheckpointSha256()))) {
            return blocked(proposal, "BLOCKED_HISTORICAL_CHECKPOINT_PIN_MISMATCH");
        }

        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(current.pinnedLogKey().publicKey());
            signature.update(priorBytes);
            if (!signature.verify(Base64.getUrlDecoder().decode(
                    prior.prior().signatureBase64Url()))) {
                return blocked(proposal, "BLOCKED_HISTORICAL_LOG_SIGNATURE");
            }
        } catch (GeneralSecurityException | RuntimeException invalid) {
            return blocked(proposal, "BLOCKED_HISTORICAL_LOG_SIGNATURE");
        }

        try {
            if (current.logRevocation().revoked(old.logId(),
                    old.verifierKeyId(), old.rootSha256(), proposal)) {
                return blocked(proposal, "BLOCKED_HISTORICAL_LOG_REVOKED");
            }
        } catch (Exception unavailable) {
            return blocked(proposal, "BLOCKED_HISTORICAL_LOG_REVOCATION_UNAVAILABLE");
        }

        if (!verifyConsistency(old.treeSize(), newer.treeSize(),
                HexFormat.of().parseHex(old.rootSha256()),
                HexFormat.of().parseHex(newer.rootSha256()),
                proof)) {
            return blocked(proposal, "BLOCKED_LOG_CONSISTENCY_PROOF");
        }

        return new ConsistencyEvidence(proposal,
                "CRYPTOGRAPHIC_APPEND_ONLY_PROOF_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                true, false, false, false, false, false, false, false);
    }

    /**
     * RFC6962 section 2.1.2 / RFC9162 audit-path approach.
     *
     * The old tree is a prefix of the new tree only if the proof reconstructs
     * BOTH the old and the new SHA256 tree roots. Covers power-of-two,
     * irregular subtrees and rightmost child folding; rejects proof padding.
     * A same-size tree may be mathematically consistent (empty identical
     * proof), but inspect() REJECTS it as non-progress / replay.
     */
    static boolean verifyConsistency(
            long oldSize, long newSize, byte[] oldRoot, byte[] newRoot,
            ConsistencyProof proof) {
        if (proof == null || oldRoot == null || newRoot == null ||
                oldRoot.length != 32 || newRoot.length != 32 ||
                oldSize <= 0 || newSize <= 0 || oldSize > newSize ||
                newSize > (1L << 50)) {
            return false;
        }
        List<String> nodes = proof.siblingsSha256();
        if (oldSize == newSize) {
            return nodes.isEmpty() && MessageDigest.isEqual(oldRoot, newRoot);
        }

        long fn = oldSize - 1;
        long sn = newSize - 1;
        while ((fn & 1L) != 0L) {
            fn >>= 1;
            sn >>= 1;
        }
        byte[] fr;
        byte[] sr;
        int offset = 0;
        if ((oldSize & (oldSize - 1)) == 0) {
            fr = oldRoot.clone();
            sr = oldRoot.clone();
        } else {
            if (nodes.isEmpty()) return false;
            fr = HexFormat.of().parseHex(nodes.get(0));
            sr = fr.clone();
            offset = 1;
        }

        for (int i = offset; i < nodes.size(); i++) {
            if (sn == 0) return false;
            byte[] next = HexFormat.of().parseHex(nodes.get(i));
            if ((fn & 1L) != 0L || fn == sn) {
                fr = ExternalCeremonyCheckpointVerifier.nodeHash(next, fr);
                sr = ExternalCeremonyCheckpointVerifier.nodeHash(next, sr);
                while ((fn & 1L) == 0L && fn != 0L) {
                    fn >>= 1;
                    sn >>= 1;
                }
            } else {
                sr = ExternalCeremonyCheckpointVerifier.nodeHash(sr, next);
            }
            fn >>= 1;
            sn >>= 1;
        }
        return sn == 0 && MessageDigest.isEqual(fr, oldRoot) &&
                MessageDigest.isEqual(sr, newRoot);
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (GeneralSecurityException missing) {
            throw new IllegalStateException("SHA256 unavailable", missing);
        }
    }

    private static boolean validHash(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    private static ConsistencyEvidence blocked(UUID proposal, String state) {
        return new ConsistencyEvidence(proposal, state,
                false, false, false, false, false, false, false, false);
    }
}
