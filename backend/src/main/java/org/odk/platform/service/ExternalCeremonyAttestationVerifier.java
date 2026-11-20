package org.odk.platform.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M3D4C2B2B2A: an INACTIVE cryptographic proof format for independent
 * Ed25519 attestations of an already-recorded V7 offline-ceremony commitment.
 *
 * No external verifier public key is shipped, enrolled, seeded or accepted
 * from an untrusted request. The caller MUST obtain its verifier public key,
 * key ID, clock and revocation decision from an external trust authority.
 *
 * A cryptographically valid attestation is still NOT an independently
 * anchored audit checkpoint, distinct-human proof, hardware key-custody
 * certification, a trusted signing key, a grant, or source-transfer authority.
 * There is intentionally no HTTP controller or production issuer.
 */
public final class ExternalCeremonyAttestationVerifier {
    private ExternalCeremonyAttestationVerifier() {}

    private static final byte[] DOMAIN =
            "DATA_COLLECTOR_OFFLINE_CEREMONY_EXTERNAL_ATTESTATION_V1"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Duration MAX_VALIDITY = Duration.ofDays(7);

    /**
     * All identity and digest fields are signed, and attester ID must be
     * distinct from the three participating web-user UUIDs. Merely distinct
     * UUIDs do not establish that humans are separate.
     */
    public record Attestation(
            UUID projectId, UUID proposalId, UUID proposerId,
            UUID reviewerId, UUID witnessId, UUID externalAttestorId,
            String proposedKeyId, String proposedKeySha256,
            String proposalEvidenceSha256, String reviewEvidenceSha256,
            String externalBundleSha256, String externalReference,
            String commitmentSha256, String externalVerifierKeyId,
            String externalCheckpointSha256, Instant witnessedAt,
            Instant attestedAt, Instant expiresAt) {
        public Attestation {
            for (UUID id : new UUID[]{projectId, proposalId, proposerId,
                    reviewerId, witnessId, externalAttestorId}) {
                if (id == null || id.equals(new UUID(0L, 0L))) {
                    throw new IllegalArgumentException(
                            "Complete non-nil ceremony identities required");
                }
            }
            if (proposerId.equals(reviewerId) ||
                    proposerId.equals(witnessId) ||
                    reviewerId.equals(witnessId) ||
                    externalAttestorId.equals(proposerId) ||
                    externalAttestorId.equals(reviewerId) ||
                    externalAttestorId.equals(witnessId) ||
                    !identifier(proposedKeyId) || !identifier(externalReference) ||
                    !identifier(externalVerifierKeyId) ||
                    !hash(proposedKeySha256) || !hash(proposalEvidenceSha256) ||
                    !hash(reviewEvidenceSha256) || !hash(externalBundleSha256) ||
                    !hash(commitmentSha256) || !hash(externalCheckpointSha256) ||
                    witnessedAt == null || attestedAt == null || expiresAt == null ||
                    attestedAt.getNano() != 0 || expiresAt.getNano() != 0 ||
                    attestedAt.isBefore(witnessedAt) ||
                    !expiresAt.isAfter(attestedAt) ||
                    expiresAt.isAfter(attestedAt.plus(MAX_VALIDITY))) {
                throw new IllegalArgumentException(
                        "Invalid ceremony identities, evidence or time window");
            }
        }
    }

    public record SignedAttestation(Attestation attestation,
                                    String signatureBase64Url) {
        public SignedAttestation {
            if (attestation == null || signatureBase64Url == null ||
                    !signatureBase64Url.matches("[A-Za-z0-9_-]{86}")) {
                throw new IllegalArgumentException(
                        "Ed25519 signature must be canonical unpadded Base64URL");
            }
            try {
                byte[] decoded = Base64.getUrlDecoder()
                        .decode(signatureBase64Url);
                if (decoded.length != 64 ||
                        !Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(decoded).equals(signatureBase64Url)) {
                    throw new IllegalArgumentException(
                            "Non-canonical Ed25519 signature");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "Invalid Ed25519 signature encoding", invalid);
            }
        }
    }

    /**
     * The expected packet must be obtained fresh from the server's
     * authenticated V7 witness endpoint/service, never from the signed
     * attestation or a caller-supplied forged JSON copy. Key ID comes from
     * an independently configured external verifier-key registry.
     */
    public record ExpectedCeremony(
            TypedMappingOfflineCeremonyService.CeremonyPacket packet,
            String trustedExternalVerifierKeyId) {
        public ExpectedCeremony {
            if (packet == null || !identifier(trustedExternalVerifierKeyId) ||
                    packet.independentlyAttested() ||
                    packet.trustedKeyRegistered() || packet.canIssueGrant() ||
                    packet.canTransfer() || packet.canSubmit()) {
                throw new IllegalArgumentException(
                        "Only a current, non-authorizing server packet is valid");
            }
        }

        boolean matches(Attestation a) {
            return a.projectId().equals(packet.projectId()) &&
                    a.proposalId().equals(packet.proposalId()) &&
                    a.proposerId().equals(packet.proposerId()) &&
                    a.reviewerId().equals(packet.reviewerId()) &&
                    a.witnessId().equals(packet.witnessId()) &&
                    a.proposedKeyId().equals(packet.proposedKeyId()) &&
                    a.proposedKeySha256().equals(packet.observedKeySha256()) &&
                    a.proposalEvidenceSha256()
                        .equals(packet.proposalEvidenceSha256()) &&
                    a.reviewEvidenceSha256()
                        .equals(packet.reviewEvidenceSha256()) &&
                    a.externalBundleSha256()
                        .equals(packet.externalBundleSha256()) &&
                    a.externalReference().equals(packet.externalReference()) &&
                    a.commitmentSha256().equals(packet.commitmentSha256()) &&
                    a.witnessedAt().equals(packet.recordedAt()) &&
                    a.externalVerifierKeyId().equals(trustedExternalVerifierKeyId);
        }
    }

    /**
     * Production verification requires a server-owned, live external trust
     * registry and revocation service. A client-supplied lambda returning
     * false is NEVER an acceptable operational trust anchor.
     */
    @FunctionalInterface
    public interface RevocationLookup {
        boolean revoked(String verifierKeyId, UUID proposalId) throws Exception;
    }

    /**
     * Even a valid independent signature has ALL operational rights false.
     * Its "SIGNATURE_VALID" status is evidence only, not positive key trust.
     */
    public record AttestationEvidence(
            UUID proposalId, String state, boolean independentlyAnchored,
            boolean trustedKeyRegistered, boolean canIssueGrant,
            boolean canReadHistoricalAnswers, boolean canTransfer,
            boolean canSubmit) {
        public AttestationEvidence {
            if (independentlyAnchored || trustedKeyRegistered ||
                    canIssueGrant || canReadHistoricalAnswers ||
                    canTransfer || canSubmit) {
                throw new IllegalArgumentException(
                        "External signature cannot confer operational rights");
            }
        }
    }

    public static AttestationEvidence inspect(
            SignedAttestation signed, PublicKey trustedExternalVerifierKey,
            ExpectedCeremony expected, Instant serverNow,
            RevocationLookup serverRevocation) {
        if (signed == null || trustedExternalVerifierKey == null ||
                expected == null || serverNow == null || serverRevocation == null) {
            return blocked(signed, "BLOCKED_MISSING_EXTERNAL_TRUST_DEPENDENCY");
        }
        Attestation a = signed.attestation();
        try {
            if (!"Ed25519".equals(trustedExternalVerifierKey.getAlgorithm()) &&
                    !"EdDSA".equals(trustedExternalVerifierKey.getAlgorithm())) {
                return blocked(signed, "BLOCKED_WRONG_EXTERNAL_SIGNATURE_KEY_TYPE");
            }
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(trustedExternalVerifierKey);
            verifier.update(canonicalBytes(a));
            if (!verifier.verify(
                    Base64.getUrlDecoder().decode(signed.signatureBase64Url()))) {
                return blocked(signed, "BLOCKED_BAD_EXTERNAL_SIGNATURE");
            }
        } catch (GeneralSecurityException | RuntimeException failure) {
            return blocked(signed, "BLOCKED_EXTERNAL_SIGNATURE_VERIFICATION_FAILED");
        }
        if (!expected.matches(a)) {
            return blocked(signed, "BLOCKED_CEREMONY_EVIDENCE_OR_KEY_ID_DRIFT");
        }
        if (serverNow.isBefore(a.attestedAt()) ||
                !serverNow.isBefore(a.expiresAt())) {
            return blocked(signed, "BLOCKED_EXTERNAL_ATTESTATION_NOT_CURRENT");
        }
        try {
            if (serverRevocation.revoked(
                    a.externalVerifierKeyId(), a.proposalId())) {
                return blocked(signed, "BLOCKED_EXTERNAL_VERIFIER_REVOKED");
            }
        } catch (Exception unavailable) {
            return blocked(signed, "BLOCKED_EXTERNAL_REVOCATION_UNAVAILABLE");
        }
        return new AttestationEvidence(a.proposalId(),
                "SIGNATURE_VALID_ONLY_EXTERNAL_CHECKPOINT_UNVERIFIED_NO_TRUST",
                false, false, false, false, false, false);
    }

    private static AttestationEvidence blocked(
            SignedAttestation signed, String code) {
        return new AttestationEvidence(
                signed == null ? null : signed.attestation().proposalId(),
                code, false, false, false, false, false, false);
    }

    /**
     * Deterministic canonical bytes enable test-only offline signing and
     * future independent public verification. NO PRIVATE KEY or signer is
     * implemented in the application by this method.
     */
    public static byte[] canonicalBytes(Attestation a) {
        Objects.requireNonNull(a, "Attestation required");
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(stream);
            out.writeInt(DOMAIN.length);
            out.write(DOMAIN);
            for (UUID id : List.of(a.projectId(), a.proposalId(),
                    a.proposerId(), a.reviewerId(), a.witnessId(),
                    a.externalAttestorId())) {
                out.writeLong(id.getMostSignificantBits());
                out.writeLong(id.getLeastSignificantBits());
            }
            for (String field : List.of(a.proposedKeyId(), a.proposedKeySha256(),
                    a.proposalEvidenceSha256(), a.reviewEvidenceSha256(),
                    a.externalBundleSha256(), a.externalReference(),
                    a.commitmentSha256(), a.externalVerifierKeyId(),
                    a.externalCheckpointSha256())) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            // V7 recorded_at can contain fractional PostgreSQL precision;
            // binding both seconds and nanoseconds prevents time truncation.
            out.writeLong(a.witnessedAt().getEpochSecond());
            out.writeInt(a.witnessedAt().getNano());
            out.writeLong(a.attestedAt().getEpochSecond());
            out.writeLong(a.expiresAt().getEpochSecond());
            out.flush();
            return stream.toByteArray();
        } catch (IOException impossibleInMemory) {
            throw new IllegalStateException(
                    "Cannot encode immutable external attestation", impossibleInMemory);
        }
    }

    private static boolean identifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static boolean hash(String value) {
        return value != null && HASH.matcher(value).matches();
    }
}
