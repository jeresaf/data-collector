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
 * M3D4C1: cryptographic envelope verification contract, NOT an activated
 * transfer/grant capability.
 *
 * No production private signing key, issuer endpoint, rule storage, source
 * answer access, client integration, or submission bypass is provided here.
 * Even successful Ed25519 verification produces explicitly NON-AUTHORIZING
 * evidence until server-owned issuer, revocation and every use-stage gate
 * have been independently qualified.
 */
public final class SignedMappingGrantVerifier {
    private SignedMappingGrantVerifier() { }

    private static final byte[] DOMAIN =
            "DATA_COLLECTOR_TYPED_MAPPING_GRANT_V1".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PATH = Pattern.compile(
            "/[A-Za-z_][A-Za-z0-9_.-]*(/[A-Za-z_][A-Za-z0-9_.-]*){1,11}");
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Duration MAX_TTL = Duration.ofHours(24);

    public enum NativeType { TEXT, INTEGER, DECIMAL, DATE }

    public record FieldRule(String path, NativeType type) {
        public FieldRule {
            if (path == null || path.length() > 255 || !PATH.matcher(path).matches() ||
                    path.toLowerCase(java.util.Locale.ROOT).contains("/meta/") ||
                    path.toLowerCase(java.util.Locale.ROOT).endsWith("/instanceid") ||
                    path.toLowerCase(java.util.Locale.ROOT).endsWith("/deprecatedid") ||
                    type == null) {
                throw new IllegalArgumentException("Invalid or sensitive field grant scope");
            }
        }
    }

    /**
     * Immutable canonical scope. No ability flag is carried inside the grant
     * since presence of a signature must never imply operational permission.
     * Field rules must be ordered lexicographically with no duplicates.
     */
    public record Grant(
            UUID grantId, UUID projectId, UUID workId, UUID recommendationId,
            UUID sourceSubmissionId, UUID targetFormVersionId,
            UUID originalReviewerId, UUID finalSignerId,
            String sourceInstanceId, String sourceXmlSha256,
            String sourceXFormSha256, String targetXFormSha256,
            String recommendationEvidenceSha256, String signingKeyId,
            Instant issuedAt, Instant notBefore, Instant expiresAt,
            List<FieldRule> fieldRules
    ) {
        public Grant {
            for (UUID uuid : List.of(grantId, projectId, workId,
                    recommendationId, sourceSubmissionId, targetFormVersionId,
                    originalReviewerId, finalSignerId)) {
                if (uuid == null || uuid.equals(new UUID(0, 0))) {
                    throw new IllegalArgumentException("Complete non-nil grant identity required");
                }
            }
            if (originalReviewerId.equals(finalSignerId) ||
                    sourceInstanceId == null ||
                    !sourceInstanceId.startsWith("uuid:") ||
                    !isCanonicalUuid(sourceInstanceId.substring(5)) ||
                    !validHash(sourceXmlSha256) || !validHash(sourceXFormSha256) ||
                    !validHash(targetXFormSha256) ||
                    !validHash(recommendationEvidenceSha256) ||
                    signingKeyId == null || !KEY_ID.matcher(signingKeyId).matches() ||
                    issuedAt == null || notBefore == null || expiresAt == null ||
                    issuedAt.getNano() != 0 || notBefore.getNano() != 0 ||
                    expiresAt.getNano() != 0 ||
                    notBefore.isBefore(issuedAt) || !expiresAt.isAfter(notBefore) ||
                    expiresAt.isAfter(issuedAt.plus(MAX_TTL)) ||
                    fieldRules == null || fieldRules.isEmpty() || fieldRules.size() > 30) {
                throw new IllegalArgumentException("Grant has untrusted identity, time or scope");
            }
            fieldRules = List.copyOf(fieldRules);
            String previous = null;
            for (FieldRule rule : fieldRules) {
                if (rule == null ||
                        (previous != null && previous.compareTo(rule.path()) >= 0)) {
                    throw new IllegalArgumentException("Grant paths must be unique and sorted");
                }
                previous = rule.path();
            }
        }
    }

    public record SignedGrant(Grant grant, String signatureBase64Url) {
        public SignedGrant {
            if (grant == null || signatureBase64Url == null ||
                    !signatureBase64Url.matches("[A-Za-z0-9_-]{86}") ||
                    !Base64.getUrlEncoder().withoutPadding().encodeToString(
                            Base64.getUrlDecoder().decode(signatureBase64Url))
                        .equals(signatureBase64Url) ||
                    Base64.getUrlDecoder().decode(signatureBase64Url).length != 64) {
                throw new IllegalArgumentException("Canonical Ed25519 signature required");
            }
        }
    }

    /**
     * The expected context must come from current trusted server work/reviewer
     * state, never from Android user input or an already-issued envelope.
     * Equality also binds the exact allowed paths AND native data types.
     */
    public record ExpectedContext(
            UUID projectId, UUID workId, UUID recommendationId,
            UUID sourceSubmissionId, UUID targetFormVersionId,
            UUID originalReviewerId, UUID finalSignerId,
            String sourceInstanceId, String sourceXmlSha256,
            String sourceXFormSha256, String targetXFormSha256,
            String recommendationEvidenceSha256, String trustedKeyId,
            List<FieldRule> expectedRules) {
        public ExpectedContext {
            expectedRules = List.copyOf(Objects.requireNonNull(expectedRules));
        }

        boolean matches(Grant g) {
            return Objects.equals(projectId, g.projectId()) &&
                    Objects.equals(workId, g.workId()) &&
                    Objects.equals(recommendationId, g.recommendationId()) &&
                    Objects.equals(sourceSubmissionId, g.sourceSubmissionId()) &&
                    Objects.equals(targetFormVersionId, g.targetFormVersionId()) &&
                    Objects.equals(originalReviewerId, g.originalReviewerId()) &&
                    Objects.equals(finalSignerId, g.finalSignerId()) &&
                    Objects.equals(sourceInstanceId, g.sourceInstanceId()) &&
                    Objects.equals(sourceXmlSha256, g.sourceXmlSha256()) &&
                    Objects.equals(sourceXFormSha256, g.sourceXFormSha256()) &&
                    Objects.equals(targetXFormSha256, g.targetXFormSha256()) &&
                    Objects.equals(recommendationEvidenceSha256,
                            g.recommendationEvidenceSha256()) &&
                    Objects.equals(trustedKeyId, g.signingKeyId()) &&
                    Objects.equals(expectedRules, g.fieldRules());
        }
    }

    /**
     * A production implementation MUST query authoritative live revocation
     * state and denial veto; a client-controlled/always-false callback cannot
     * satisfy release qualification. No such production implementation exists.
     */
    @FunctionalInterface
    public interface RevocationCheck {
        boolean isRevoked(UUID grantId, String keyId) throws Exception;
    }

    public record SignatureEvidence(
            UUID grantId, String state,
            boolean canReadHistoricalAnswers, boolean canTransfer,
            boolean canFinalize, boolean canSubmit) {
        public SignatureEvidence {
            if (canReadHistoricalAnswers || canTransfer ||
                    canFinalize || canSubmit) {
                throw new IllegalArgumentException(
                        "Signature verification cannot authorize an operational transfer");
            }
        }
    }

    /**
     * Verifies the exact domain-separated, canonical signed tuple under a
     * caller-supplied TRUSTED Ed25519 public key and an independently sourced
     * expected context, current clock, and revocation check. Even a positive
     * signature has NO operational authority.
     */
    public static SignatureEvidence inspect(
            SignedGrant signed, PublicKey trustedKey, ExpectedContext expected,
            Instant now, RevocationCheck revocations) {
        if (signed == null || trustedKey == null || expected == null ||
                now == null || revocations == null) {
            return blocked(signed, "BLOCKED_MISSING_SERVER_VERIFICATION_DEPENDENCY");
        }
        Grant g = signed.grant();
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(trustedKey);
            signature.update(canonicalBytes(g));
            if (!signature.verify(
                    Base64.getUrlDecoder().decode(signed.signatureBase64Url()))) {
                return blocked(signed, "BLOCKED_BAD_SIGNATURE");
            }
        } catch (GeneralSecurityException | RuntimeException failure) {
            return blocked(signed, "BLOCKED_UNTRUSTED_KEY_OR_SIGNATURE");
        }
        if (!expected.matches(g)) {
            return blocked(signed, "BLOCKED_CONTEXT_OR_SCOPE_DRIFT");
        }
        if (now.isBefore(g.notBefore()) || !now.isBefore(g.expiresAt())) {
            return blocked(signed, "BLOCKED_NOT_YET_VALID_OR_EXPIRED");
        }
        try {
            if (revocations.isRevoked(g.grantId(), g.signingKeyId())) {
                return blocked(signed, "BLOCKED_REVOKED_OR_DENIED");
            }
        } catch (Exception failure) {
            return blocked(signed, "BLOCKED_REVOCATION_UNAVAILABLE");
        }
        return new SignatureEvidence(g.grantId(),
                "VERIFIED_SIGNATURE_ONLY_NO_OPERATIONAL_GRANT",
                false, false, false, false);
    }

    private static SignatureEvidence blocked(SignedGrant signed, String reason) {
        return new SignatureEvidence(
                signed == null ? null : signed.grant().grantId(),
                reason, false, false, false, false);
    }

    /**
     * Package-private for a synthetic TEST-ONLY signer. Production key custody
     * and grant issuance are intentionally NOT supplied in this milestone.
     */
    static byte[] canonicalBytes(Grant grant) {
        Objects.requireNonNull(grant, "Verified grant scope required");
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(output);
            data.writeInt(DOMAIN.length);
            data.write(DOMAIN);
            for (UUID id : List.of(grant.grantId(), grant.projectId(),
                    grant.workId(), grant.recommendationId(),
                    grant.sourceSubmissionId(), grant.targetFormVersionId(),
                    grant.originalReviewerId(), grant.finalSignerId())) {
                data.writeLong(id.getMostSignificantBits());
                data.writeLong(id.getLeastSignificantBits());
            }
            for (String value : List.of(grant.sourceInstanceId(),
                    grant.sourceXmlSha256(), grant.sourceXFormSha256(),
                    grant.targetXFormSha256(),
                    grant.recommendationEvidenceSha256(), grant.signingKeyId())) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                data.writeInt(bytes.length);
                data.write(bytes);
            }
            data.writeLong(grant.issuedAt().getEpochSecond());
            data.writeLong(grant.notBefore().getEpochSecond());
            data.writeLong(grant.expiresAt().getEpochSecond());
            data.writeInt(grant.fieldRules().size());
            for (FieldRule rule : grant.fieldRules()) {
                byte[] bytes = rule.path().getBytes(StandardCharsets.UTF_8);
                data.writeInt(bytes.length);
                data.write(bytes);
                data.writeInt(rule.type().ordinal());
            }
            data.flush();
            return output.toByteArray();
        } catch (IOException impossibleInMemory) {
            throw new IllegalStateException("Cannot encode immutable grant", impossibleInMemory);
        }
    }

    private static boolean validHash(String digest) {
        return digest != null && HASH.matcher(digest).matches();
    }

    private static boolean isCanonicalUuid(String text) {
        try {
            return UUID.fromString(text).toString().equals(text);
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
