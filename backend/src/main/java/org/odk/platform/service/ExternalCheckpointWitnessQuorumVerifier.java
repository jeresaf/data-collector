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
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M3D4C2B2B2C2: offline two-witness corroboration of a previously verified,
 * consistently extended V7 log checkpoint. The two independently pinned
 * Ed25519 witness keys sign the exact same checkpoint bytes digest.
 *
 * No external publisher, HTTPS fetcher, independently managed trust store,
 * witness registry, durable outside-DB anchor, or real witness key is wired
 * into this class. Two signatures supplied by one malicious operator do NOT
 * prove independent publication or anti-split-view monitoring. This is a
 * non-authorizing verification contract, not an activation mechanism.
 */
public final class ExternalCheckpointWitnessQuorumVerifier {
    private ExternalCheckpointWitnessQuorumVerifier() {}

    private static final byte[] DOMAIN =
            "DATA_COLLECTOR_EXTERNAL_LOG_WITNESS_RECEIPT_V1"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Duration MAX_OBSERVATION_DELAY = Duration.ofHours(6);
    private static final Duration MAX_RECEIPT_VALIDITY = Duration.ofHours(24);

    /**
     * A witness CLAIMS to have observed a checkpoint. This signature does not
     * attest that an independently operated publisher really published it.
     * All signed times are whole UTC seconds; tree root and size are bound.
     */
    public record WitnessReceipt(
            String witnessId, String witnessVerifierKeyId,
            String logId, String logVerifierKeyId, long treeSize,
            String rootSha256, String canonicalCheckpointSha256,
            Instant checkpointAt, Instant observedAt, Instant expiresAt) {
        public WitnessReceipt {
            if (!validId(witnessId) || !validId(witnessVerifierKeyId) ||
                    !validId(logId) || !validId(logVerifierKeyId) ||
                    treeSize < 1 || treeSize > (1L << 50) ||
                    !validHash(rootSha256) ||
                    !validHash(canonicalCheckpointSha256) ||
                    checkpointAt == null || observedAt == null ||
                    expiresAt == null ||
                    checkpointAt.getNano() != 0 ||
                    observedAt.getNano() != 0 || expiresAt.getNano() != 0 ||
                    observedAt.isBefore(checkpointAt) ||
                    observedAt.isAfter(checkpointAt.plus(MAX_OBSERVATION_DELAY)) ||
                    !expiresAt.isAfter(observedAt) ||
                    expiresAt.isAfter(observedAt.plus(MAX_RECEIPT_VALIDITY))) {
                throw new IllegalArgumentException(
                        "Invalid or unbounded external witness receipt");
            }
        }
    }

    public record SignedReceipt(WitnessReceipt receipt,
                                String signatureBase64Url) {
        public SignedReceipt {
            if (receipt == null || !canonicalSignature(signatureBase64Url)) {
                throw new IllegalArgumentException(
                        "Canonical Ed25519 witness signature required");
            }
        }
    }

    /**
     * A future *server-owned* provider must load both public keys and SPKI
     * fingerprints from a separately governed immutable trust catalog.
     * This record does not verify independent pin provenance by itself.
     */
    public record TrustedWitness(
            String witnessId, String verifierKeyId, PublicKey publicKey,
            String externallyPinnedSpkiSha256) {
        public TrustedWitness {
            if (!validId(witnessId) || !validId(verifierKeyId) ||
                    publicKey == null ||
                    !validHash(externallyPinnedSpkiSha256)) {
                throw new IllegalArgumentException(
                        "Incomplete externally trusted witness key");
            }
        }
    }

    public record WitnessProof(SignedReceipt signed,
                               TrustedWitness trusted) {
        public WitnessProof {
            if (signed == null || trusted == null) {
                throw new IllegalArgumentException(
                        "Signed receipt and independent witness trust required");
            }
        }
    }

    @FunctionalInterface
    public interface WitnessRevocationLookup {
        boolean revoked(String witnessId, String verifierKeyId,
                        String checkpointSha256, UUID proposalId) throws Exception;
    }

    /**
     * Nothing in a signed receipt authorizes reading, transforming or sending
     * historical records. Even a two-signature success cannot claim actual
     * publication or cross-organization witness independence.
     */
    public record Evidence(
            UUID proposalId, String state, boolean cryptographicWitnessAgreement,
            boolean independentlyPublished, boolean antiSplitViewVerified,
            boolean trustedKeyRegistered, boolean canIssueGrant,
            boolean canReadHistoricalAnswers, boolean canTransfer,
            boolean canSubmit) {
        public Evidence {
            if (independentlyPublished || antiSplitViewVerified ||
                    trustedKeyRegistered || canIssueGrant ||
                    canReadHistoricalAnswers || canTransfer || canSubmit ||
                    (cryptographicWitnessAgreement &&
                     !"TWO_WITNESS_SIGNATURES_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST"
                            .equals(state))) {
                throw new IllegalArgumentException(
                        "Unpublished witness receipts cannot grant operational rights");
            }
        }
    }

    /**
     * Requires signed V7 evidence -> current checkpoint inclusion -> prior
     * signed historical checkpoint consistency before accepting the two
     * witness signatures. The historical checkpoint pin and witness key
     * fingerprints MUST be independently sourced by a future wrapper.
     */
    public static Evidence inspect(
            ExternalCeremonyLogConsistencyVerifier.CurrentInclusion current,
            ExternalCeremonyLogConsistencyVerifier.HistoricalAnchor prior,
            ExternalCeremonyLogConsistencyVerifier.ConsistencyProof consistency,
            WitnessProof witnessA, WitnessProof witnessB,
            Instant serverNow, WitnessRevocationLookup revocation) {
        UUID proposalId = current == null || current.attestation() == null ?
                null : current.attestation().attestation().proposalId();
        if (current == null || prior == null || consistency == null ||
                witnessA == null || witnessB == null ||
                serverNow == null || revocation == null ||
                current.newer() == null || current.pinnedLogKey() == null) {
            return blocked(proposalId, "BLOCKED_MISSING_INDEPENDENT_WITNESS_TRUST");
        }
        var earlier = ExternalCeremonyLogConsistencyVerifier.inspect(
                current, prior, consistency, serverNow);
        if (!"CRYPTOGRAPHIC_APPEND_ONLY_PROOF_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST"
                .equals(earlier.state())) {
            return blocked(proposalId, "BLOCKED_PREVIOUS_LOG_CONSISTENCY_INVALID");
        }

        var checkpoint = current.newer().checkpoint();
        String checkpointHash = sha256(ExternalCeremonyCheckpointVerifier
                .canonicalCheckpointBytes(checkpoint));
        var first = validateWitness(witnessA, checkpoint, checkpointHash,
                current, proposalId, serverNow, revocation);
        if (first != null) return blocked(proposalId, first);
        var second = validateWitness(witnessB, checkpoint, checkpointHash,
                current, proposalId, serverNow, revocation);
        if (second != null) return blocked(proposalId, second);

        if (witnessA.trusted().witnessId().equals(
                    witnessB.trusted().witnessId()) ||
                witnessA.trusted().verifierKeyId().equals(
                    witnessB.trusted().verifierKeyId()) ||
                Arrays.equals(witnessA.trusted().publicKey().getEncoded(),
                        witnessB.trusted().publicKey().getEncoded()) ||
                witnessA.signed().receipt().witnessId().equals(
                    witnessB.signed().receipt().witnessId())) {
            return blocked(proposalId, "BLOCKED_DUPLICATE_OR_SHARED_WITNESS_IDENTITY");
        }
        // Evaluate both *authenticated* witnesses before checking head
        // agreement; tampered unsigned text is never an equivocation signal.
        var a = witnessA.signed().receipt();
        var b = witnessB.signed().receipt();
        if (!sameHead(a, b)) {
            if (a.logId().equals(b.logId()) &&
                    a.logVerifierKeyId().equals(b.logVerifierKeyId()) &&
                    a.treeSize() == b.treeSize() &&
                    !a.rootSha256().equals(b.rootSha256())) {
                return blocked(proposalId, "BLOCKED_SIGNED_WITNESS_EQUIVOCATION");
            }
            return blocked(proposalId, "BLOCKED_SIGNED_WITNESS_HEAD_MISMATCH");
        }
        if (!matchesCheckpoint(a, checkpoint, checkpointHash)) {
            return blocked(proposalId, "BLOCKED_SIGNED_WITNESS_HEAD_MISMATCH");
        }

        return new Evidence(proposalId,
                "TWO_WITNESS_SIGNATURES_ONLY_PUBLICATION_UNVERIFIED_NO_TRUST",
                true, false, false, false, false, false, false, false);
    }

    /**
     * Canonical receipt binds witness identity, checkpoint root, log key,
     * tree size and observed time without JSON serialization ambiguity.
     * Encoding is NOT signing and no private key is present in the app.
     */
    public static byte[] canonicalBytes(WitnessReceipt r) {
        if (r == null) throw new IllegalArgumentException("Receipt required");
        try {
            var stream = new ByteArrayOutputStream();
            var out = new DataOutputStream(stream);
            putBytes(out, DOMAIN);
            for (String id : List.of(r.witnessId(), r.witnessVerifierKeyId(),
                    r.logId(), r.logVerifierKeyId())) {
                putBytes(out, id.getBytes(StandardCharsets.UTF_8));
            }
            out.writeLong(r.treeSize());
            out.write(HexFormat.of().parseHex(r.rootSha256()));
            out.write(HexFormat.of().parseHex(r.canonicalCheckpointSha256()));
            out.writeLong(r.checkpointAt().getEpochSecond());
            out.writeLong(r.observedAt().getEpochSecond());
            out.writeLong(r.expiresAt().getEpochSecond());
            out.flush();
            return stream.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode witness receipt", impossible);
        }
    }

    private static String validateWitness(
            WitnessProof proof,
            ExternalCeremonyCheckpointVerifier.Checkpoint checkpoint,
            String checkpointHash,
            ExternalCeremonyLogConsistencyVerifier.CurrentInclusion current,
            UUID proposalId, Instant now, WitnessRevocationLookup revocation) {
        var receipt = proof.signed().receipt();
        var trusted = proof.trusted();
        byte[] spki = trusted.publicKey().getEncoded();
        if (!trusted.witnessId().equals(receipt.witnessId()) ||
                !trusted.verifierKeyId().equals(receipt.witnessVerifierKeyId()) ||
                spki == null ||
                !sha256(spki).equals(trusted.externallyPinnedSpkiSha256()) ||
                Arrays.equals(spki, current.pinnedLogKey().publicKey().getEncoded()) ||
                Arrays.equals(spki, current.attestorKey().getEncoded())) {
            return "BLOCKED_UNTRUSTED_OR_SHARED_WITNESS_SIGNER";
        }
        try {
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(trusted.publicKey());
            verifier.update(canonicalBytes(receipt));
            if (!verifier.verify(Base64.getUrlDecoder().decode(
                    proof.signed().signatureBase64Url()))) {
                return "BLOCKED_BAD_EXTERNAL_WITNESS_SIGNATURE";
            }
        } catch (GeneralSecurityException | RuntimeException invalid) {
            return "BLOCKED_BAD_EXTERNAL_WITNESS_SIGNATURE";
        }
        if (now.isBefore(receipt.observedAt()) ||
                !now.isBefore(receipt.expiresAt()) ||
                receipt.observedAt().isAfter(checkpoint.validUntil())) {
            return "BLOCKED_EXPIRED_OR_FUTURE_WITNESS_RECEIPT";
        }
        try {
            if (revocation.revoked(trusted.witnessId(),
                    trusted.verifierKeyId(),
                    receipt.canonicalCheckpointSha256(), proposalId)) {
                return "BLOCKED_EXTERNAL_WITNESS_REVOKED";
            }
        } catch (Exception unavailable) {
            return "BLOCKED_EXTERNAL_WITNESS_REVOCATION_UNAVAILABLE";
        }
        return null;
    }

    private static boolean matchesCheckpoint(
            WitnessReceipt r,
            ExternalCeremonyCheckpointVerifier.Checkpoint checkpoint,
            String checkpointHash) {
        return r.checkpointAt().equals(checkpoint.checkpointAt()) &&
                r.logId().equals(checkpoint.logId()) &&
                r.logVerifierKeyId().equals(checkpoint.verifierKeyId()) &&
                r.treeSize() == checkpoint.treeSize() &&
                r.rootSha256().equals(checkpoint.rootSha256()) &&
                r.canonicalCheckpointSha256().equals(checkpointHash);
    }

    private static boolean sameHead(WitnessReceipt a, WitnessReceipt b) {
        return a.logId().equals(b.logId()) &&
                a.logVerifierKeyId().equals(b.logVerifierKeyId()) &&
                a.treeSize() == b.treeSize() &&
                a.rootSha256().equals(b.rootSha256()) &&
                a.canonicalCheckpointSha256().equals(b.canonicalCheckpointSha256());
    }

    private static void putBytes(DataOutputStream out, byte[] bytes)
            throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("SHA256 unavailable", unavailable);
        }
    }

    private static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    private static boolean validHash(String hash) {
        return hash != null && HASH.matcher(hash).matches();
    }

    private static boolean canonicalSignature(String signature) {
        if (signature == null || !signature.matches("[A-Za-z0-9_-]{86}")) {
            return false;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(signature);
            return bytes.length == 64 &&
                    Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(bytes).equals(signature);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static Evidence blocked(UUID proposalId, String reason) {
        return new Evidence(proposalId, reason,
                false, false, false, false, false, false, false, false);
    }
}
