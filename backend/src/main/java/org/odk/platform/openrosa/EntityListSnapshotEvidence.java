package org.odk.platform.openrosa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Inert review envelope for a candidate Entity List snapshot.
 *
 * <p>Creation validates bytes but does not prove their origin. An authoritative
 * service must create and durably retain these records from trusted storage,
 * assign monotonically increasing revisions, and approve any removal before
 * this can support an integrity/tombstone endpoint. It is not wired into one.</p>
 */
public final class EntityListSnapshotEvidence {
    private EntityListSnapshotEvidence() {
    }

    public record Snapshot(
            UUID projectId,
            UUID formId,
            String filename,
            String mediaType,
            long revision,
            String md5,
            String sha256,
            long rowCount) {
    }

    public static Optional<Snapshot> fromVerifiedBytes(
            UUID projectId, UUID formId, String filename, long revision,
            Map<String, Object> metadata, byte[] bytes, String recordedMd5,
            Long expectedRowCount) {
        if (projectId == null || formId == null
                || !EntityListManifestPreflight.validTypedFilename(filename)
                || revision < 1 || expectedRowCount == null || expectedRowCount < 1) {
            return Optional.empty();
        }
        Optional<String> type = EntityListManifestPreflight.verifiedCandidate(
                metadata, bytes, recordedMd5, expectedRowCount);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        try {
            String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
            String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return Optional.of(new Snapshot(projectId, formId, filename, type.get(),
                    revision, md5, sha256, expectedRowCount));
        } catch (NoSuchAlgorithmException e) {
            return Optional.empty();
        }
    }

    /**
     * Validates both immutable evidence descriptors against caller-supplied
     * bytes before computing possible missing ids. Never performs deletion.
     */
    public static Optional<Set<UUID>> reviewMissingIds(
            Snapshot previous, byte[] previousBytes,
            Snapshot next, byte[] nextBytes) {
        if (previous == null || next == null
                || !previous.projectId().equals(next.projectId())
                || !previous.formId().equals(next.formId())
                || !previous.filename().equals(next.filename())
                || !previous.mediaType().equals(next.mediaType())
                || previous.revision() >= next.revision()
                || !matchesEvidence(previous, previousBytes)
                || !matchesEvidence(next, nextBytes)) {
            return Optional.empty();
        }
        Map<String, Object> type = Map.of(EntityListManifestPreflight.METADATA_KEY,
                previous.mediaType());
        return EntityListManifestPreflight.removedIdsBetweenVerifiedSnapshots(
                previous.filename(), type, previousBytes, previous.md5(),
                next.filename(), type, nextBytes, next.md5());
    }

    private static boolean matchesEvidence(Snapshot evidence, byte[] bytes) {
        if (bytes == null || evidence.rowCount() < 1
                || !EntityListManifestPreflight.validTypedFilename(evidence.filename())) {
            return false;
        }
        Map<String, Object> metadata = Map.of(EntityListManifestPreflight.METADATA_KEY,
                evidence.mediaType());
        if (EntityListManifestPreflight.verifiedCandidate(
                metadata, bytes, evidence.md5(), evidence.rowCount()).isEmpty()) {
            return false;
        }
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(bytes);
            return MessageDigest.isEqual(actual,
                    HexFormat.of().parseHex(evidence.sha256()));
        } catch (NoSuchAlgorithmException | IllegalArgumentException e) {
            return false;
        }
    }
}
