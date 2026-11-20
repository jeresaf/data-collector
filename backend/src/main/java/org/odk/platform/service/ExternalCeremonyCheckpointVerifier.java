package org.odk.platform.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M3D4C2B2B2B: offline cryptographic CHECKPOINT inclusion proof ONLY.
 *
 * A trusted, externally-pinned Ed25519 log key authenticates a fixed Merkle
 * tree head (RFC6962-style SHA-256 domain separation) and the proof includes
 * the exact V7 commitment referenced by the independently signed PR18
 * attestation. Neither the log's PUBLICATION nor its historical consistency
 * with previous heads is checked. No external key is configured or seeded.
 *
 * No private key, production controller, key activation, grant issuance,
 * historical-answer retrieval, Android import or submission path exists.
 */
public final class ExternalCeremonyCheckpointVerifier {
    private ExternalCeremonyCheckpointVerifier() {}

    private static final byte[] CHECKPOINT_DOMAIN =
            "DATA_COLLECTOR_EXTERNAL_CEREMONY_LOG_CHECKPOINT_V1"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LEAF_DOMAIN =
            "DATA_COLLECTOR_V7_CEREMONY_COMMITMENT_LOG_LEAF_V1"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final Pattern ID =
            Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Duration MAX_CHECKPOINT_VALIDITY = Duration.ofHours(48);
    private static final long MAX_TREE_SIZE = 1L << 50;

    /** Hashes are lower-case hex; tree size is nonempty and intentionally bounded. */
    public record Checkpoint(
            String logId, String verifierKeyId, long treeSize,
            String rootSha256, Instant checkpointAt, Instant validUntil) {
        public Checkpoint {
            if (!validId(logId) || !validId(verifierKeyId) ||
                    treeSize < 1 || treeSize > MAX_TREE_SIZE ||
                    !validHash(rootSha256) ||
                    checkpointAt == null || validUntil == null ||
                    checkpointAt.getNano() != 0 || validUntil.getNano() != 0 ||
                    !validUntil.isAfter(checkpointAt) ||
                    validUntil.isAfter(checkpointAt.plus(MAX_CHECKPOINT_VALIDITY))) {
                throw new IllegalArgumentException("Invalid or unbounded signed checkpoint");
            }
        }
    }

    public record SignedCheckpoint(Checkpoint checkpoint,
                                   String signatureBase64Url) {
        public SignedCheckpoint {
            if (checkpoint == null || !canonicalSignature(signatureBase64Url)) {
                throw new IllegalArgumentException(
                        "Canonical 64-byte Ed25519 log checkpoint signature required");
            }
        }
    }

    /** RFC6962 inclusion sibling hashes from leaf to signed root. */
    public record InclusionProof(long leafIndex, List<String> siblingsSha256) {
        public InclusionProof {
            if (leafIndex < 0 || siblingsSha256 == null ||
                    siblingsSha256.size() > 64 ||
                    siblingsSha256.stream().anyMatch(s -> !validHash(s))) {
                throw new IllegalArgumentException("Malformed bounded Merkle inclusion proof");
            }
            siblingsSha256 = List.copyOf(siblingsSha256);
        }
    }

    /**
     * Trust must come from an independently governed, pinned log-key catalog.
     * This class verifies a fingerprint against the supplied key but CANNOT
     * establish that the caller fetched that fingerprint from a trust root.
     * No production key catalog, enrollment route or default key is provided.
     */
    public record TrustedLogKey(
            String logId, String keyId, PublicKey publicKey,
            String externallyPinnedSpkiSha256) {
        public TrustedLogKey {
            if (!validId(logId) || !validId(keyId) || publicKey == null ||
                    !validHash(externallyPinnedSpkiSha256)) {
                throw new IllegalArgumentException(
                        "Missing independently pinned log verification key");
            }
        }
    }

    @FunctionalInterface
    public interface LogRevocationLookup {
        boolean revoked(String logId, String keyId,
                        String checkpointRootSha256, UUID proposalId) throws Exception;
    }

    /**
     * Inclusion is a cryptographic statement under the supplied log key;
     * publication to an independent monitor and split-view/rollback defenses
     * are NOT established by any output from this verifier.
     */
    public record Evidence(
            UUID proposalId, String state, boolean cryptographicInclusionVerified,
            boolean independentlyPublished, boolean historicalConsistencyVerified,
            boolean trustedKeyRegistered, boolean canIssueGrant,
            boolean canReadHistoricalAnswers, boolean canTransfer,
            boolean canSubmit) {
        public Evidence {
            if (independentlyPublished || historicalConsistencyVerified ||
                    trustedKeyRegistered || canIssueGrant ||
                    canReadHistoricalAnswers || canTransfer || canSubmit ||
                    (cryptographicInclusionVerified &&
                     !"CRYPTOGRAPHIC_INCLUSION_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST"
                            .equals(state))) {
                throw new IllegalArgumentException(
                        "Offline checkpoint proof cannot confer trust or operational rights");
            }
        }
    }

    /**
     * Validates BOTH the independent attestation and a separately signed
     * Merkle log checkpoint and its inclusion path to the same V7 commitment.
     *
     * No caller-provided public key/fingerprint or lambda can be an
     * operational source of trust. An actual independent log publisher,
     * trust-store, proof downloader, revocation service and consistency
     * monitor have not been integrated. This method stays non-authorizing.
     */
    public static Evidence inspect(
            ExternalCeremonyAttestationVerifier.SignedAttestation attestation,
            PublicKey independentAttestorKey,
            ExternalCeremonyAttestationVerifier.ExpectedCeremony currentPacket,
            ExternalCeremonyAttestationVerifier.RevocationLookup attestorRevocation,
            SignedCheckpoint signedCheckpoint, InclusionProof proof,
            TrustedLogKey independentlyPinnedLogKey, Instant serverNow,
            LogRevocationLookup logRevocation) {
        UUID proposal = attestation == null ? null :
                attestation.attestation().proposalId();
        if (attestation == null || independentAttestorKey == null ||
                currentPacket == null || attestorRevocation == null ||
                signedCheckpoint == null || proof == null ||
                independentlyPinnedLogKey == null || serverNow == null ||
                logRevocation == null) {
            return blocked(proposal, "BLOCKED_MISSING_LOG_OR_ATTESTATION_TRUST");
        }

        var signedEvidence = ExternalCeremonyAttestationVerifier.inspect(
                attestation, independentAttestorKey, currentPacket,
                serverNow, attestorRevocation);
        if (!"SIGNATURE_VALID_ONLY_EXTERNAL_CHECKPOINT_UNVERIFIED_NO_TRUST"
                .equals(signedEvidence.state())) {
            return blocked(proposal, "BLOCKED_INDEPENDENT_ATTESTATION_INVALID");
        }

        var a = attestation.attestation();
        var checkpoint = signedCheckpoint.checkpoint();
        var trust = independentlyPinnedLogKey;
        if (!trust.logId().equals(checkpoint.logId()) ||
                !trust.keyId().equals(checkpoint.verifierKeyId()) ||
                !validHash(a.externalCheckpointSha256()) ||
                !MessageDigest.isEqual(
                    hash(canonicalCheckpointBytes(checkpoint)),
                    HexFormat.of().parseHex(a.externalCheckpointSha256()))) {
            return blocked(proposal, "BLOCKED_CHECKPOINT_ID_OR_DIGEST_DRIFT");
        }
        if (checkpoint.checkpointAt().isBefore(a.witnessedAt()) ||
                checkpoint.checkpointAt().isAfter(a.attestedAt()) ||
                serverNow.isBefore(checkpoint.checkpointAt()) ||
                !serverNow.isBefore(checkpoint.validUntil())) {
            return blocked(proposal, "BLOCKED_CHECKPOINT_TIME_WINDOW");
        }
        byte[] trustedSpki = trust.publicKey().getEncoded();
        if (trustedSpki == null ||
                !MessageDigest.isEqual(
                        hash(trustedSpki),
                        HexFormat.of().parseHex(trust.externallyPinnedSpkiSha256())) ||
                Arrays.equals(trustedSpki, independentAttestorKey.getEncoded())) {
            return blocked(proposal, "BLOCKED_UNPINNED_OR_SHARED_LOG_KEY");
        }
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(trust.publicKey());
            signature.update(canonicalCheckpointBytes(checkpoint));
            if (!signature.verify(Base64.getUrlDecoder().decode(
                    signedCheckpoint.signatureBase64Url()))) {
                return blocked(proposal, "BLOCKED_LOG_CHECKPOINT_SIGNATURE");
            }
        } catch (GeneralSecurityException | RuntimeException invalid) {
            return blocked(proposal, "BLOCKED_LOG_CHECKPOINT_SIGNATURE");
        }

        if (!validInclusion(
                leafHash(a), checkpoint.treeSize(), proof,
                HexFormat.of().parseHex(checkpoint.rootSha256()))) {
            return blocked(proposal, "BLOCKED_MERKLE_INCLUSION_PROOF");
        }
        try {
            if (logRevocation.revoked(trust.logId(), trust.keyId(),
                    checkpoint.rootSha256(), a.proposalId())) {
                return blocked(proposal, "BLOCKED_EXTERNAL_LOG_REVOKED");
            }
        } catch (Exception unavailable) {
            return blocked(proposal, "BLOCKED_EXTERNAL_LOG_REVOCATION_UNAVAILABLE");
        }
        return new Evidence(proposal,
                "CRYPTOGRAPHIC_INCLUSION_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                true, false, false, false, false, false, false, false);
    }

    /**
     * Canonical checkpoint format signed by an independent log key.
     * The digest of these bytes is referenced in the prior signed attestation.
     */
    public static byte[] canonicalCheckpointBytes(Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "Checkpoint required");
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            putBytes(out, CHECKPOINT_DOMAIN);
            putBytes(out, checkpoint.logId().getBytes(StandardCharsets.UTF_8));
            putBytes(out, checkpoint.verifierKeyId().getBytes(StandardCharsets.UTF_8));
            out.writeLong(checkpoint.treeSize());
            out.write(HexFormat.of().parseHex(checkpoint.rootSha256()));
            out.writeLong(checkpoint.checkpointAt().getEpochSecond());
            out.writeLong(checkpoint.validUntil().getEpochSecond());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode checkpoint", impossible);
        }
    }

    /**
     * The log leaf is the original V7 COMMITMENT, not the signed attestation
     * itself: otherwise the attestation's checkpoint SHA would introduce a
     * circular commitment. RFC6962 leaf hashing uses SHA256(0x00 || bytes).
     */
    public static byte[] canonicalCommitmentLeaf(
            ExternalCeremonyAttestationVerifier.Attestation a) {
        Objects.requireNonNull(a, "Signed attestation required");
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            putBytes(out, LEAF_DOMAIN);
            uuid(out, a.projectId());
            uuid(out, a.proposalId());
            out.write(HexFormat.of().parseHex(a.commitmentSha256()));
            out.write(HexFormat.of().parseHex(a.proposedKeySha256()));
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode V7 log leaf", impossible);
        }
    }

    static byte[] leafHash(ExternalCeremonyAttestationVerifier.Attestation a) {
        return hash(concat((byte) 0, canonicalCommitmentLeaf(a)));
    }

    static byte[] nodeHash(byte[] left, byte[] right) {
        if (left == null || right == null || left.length != 32 || right.length != 32) {
            throw new IllegalArgumentException("Full SHA-256 Merkle child required");
        }
        byte[] bytes = new byte[65];
        bytes[0] = 1; // RFC6962 internal node domain
        System.arraycopy(left, 0, bytes, 1, 32);
        System.arraycopy(right, 0, bytes, 33, 32);
        return hash(bytes);
    }

    /**
     * RFC6962 audit-path traversal with rightmost-uneven-tree handling.
     * A proof must consume EXACTLY enough siblings to reach the root.
     * Rejects missing/extraneous siblings, an out-of-range leaf and
     * ambiguous pair ordering. Independent consistency is NOT checked.
     */
    static boolean validInclusion(
            byte[] leafHash, long treeSize, InclusionProof proof, byte[] root) {
        if (leafHash == null || leafHash.length != 32 || root == null ||
                root.length != 32 || proof == null ||
                treeSize < 1 || treeSize > MAX_TREE_SIZE ||
                proof.leafIndex() >= treeSize) {
            return false;
        }
        long fn = proof.leafIndex();
        long sn = treeSize - 1;
        byte[] value = leafHash.clone();
        for (String hex : proof.siblingsSha256()) {
            if (sn == 0) return false;
            byte[] sibling = HexFormat.of().parseHex(hex);
            if ((fn & 1L) == 1L || fn == sn) {
                value = nodeHash(sibling, value);
                while ((fn & 1L) == 0L && fn != 0) {
                    fn >>= 1;
                    sn >>= 1;
                }
            } else {
                value = nodeHash(value, sibling);
            }
            fn >>= 1;
            sn >>= 1;
        }
        return sn == 0 && MessageDigest.isEqual(value, root);
    }

    private static void putBytes(DataOutputStream out, byte[] bytes)
            throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static byte[] concat(byte first, byte[] following) {
        byte[] combined = new byte[following.length + 1];
        combined[0] = first;
        System.arraycopy(following, 0, combined, 1, following.length);
        return combined;
    }

    private static byte[] hash(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (GeneralSecurityException absent) {
            throw new IllegalStateException("SHA-256 unavailable", absent);
        }
    }

    private static boolean canonicalSignature(String encoded) {
        if (encoded == null || !encoded.matches("[A-Za-z0-9_-]{86}"))
            return false;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            return decoded.length == 64 &&
                    Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(decoded).equals(encoded);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean validId(String s) {
        return s != null && ID.matcher(s).matches();
    }

    private static boolean validHash(String s) {
        return s != null && SHA256.matcher(s).matches();
    }

    private static Evidence blocked(UUID proposalId, String reason) {
        return new Evidence(proposalId, reason,
                false, false, false, false, false, false, false, false);
    }
}
