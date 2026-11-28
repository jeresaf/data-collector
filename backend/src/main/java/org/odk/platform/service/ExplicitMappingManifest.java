package org.odk.platform.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Answer-free explicit mapping proposal, compatible with Android V1.
 * This manifest is NOT a signed grant, trusted recommendation, or permission.
 * The server must independently derive and validate source/target inventories,
 * actor/project/consent, semantic review and native values before any use.
 */
public record ExplicitMappingManifest(
        UUID workId, UUID sourceSubmissionId, UUID targetFormVersionId,
        String sourceXFormSha256, String targetXFormSha256, List<FieldRule> rules) {

    private static final Pattern PATH = Pattern.compile(
            "/[A-Za-z_][A-Za-z0-9_.-]*(/[A-Za-z_][A-Za-z0-9_.-]*){1,11}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> RESERVED = Set.of("meta", "instanceid", "deprecatedid", "deviceid");

    public enum ScalarType { TEXT, INTEGER, DECIMAL, DATE }

    public record FieldRule(String sourcePath, String targetPath, ScalarType type) {
        public FieldRule {
            if (!validPath(sourcePath) || !validPath(targetPath) || type == null)
                throw new IllegalArgumentException("Invalid mapping rule");
        }
    }

    public ExplicitMappingManifest {
        if (!nonNil(workId) || !nonNil(sourceSubmissionId) || !nonNil(targetFormVersionId)
                || !validHash(sourceXFormSha256) || !validHash(targetXFormSha256)
                || rules == null || rules.isEmpty() || rules.size() > 30)
            throw new IllegalArgumentException("Invalid mapping manifest scope");
        var source = new HashSet<String>();
        var target = new HashSet<String>();
        for (var rule : rules) {
            if (rule == null || !source.add(rule.sourcePath()) || !target.add(rule.targetPath()))
                throw new IllegalArgumentException("Mapping rules must be one-to-one");
        }
        rules = rules.stream().sorted(Comparator.comparing(FieldRule::targetPath)).toList();
    }

    public byte[] canonicalBytes() {
        StringBuilder value = new StringBuilder("DATA_COLLECTOR_EXPLICIT_MAPPING_V1\n");
        for (Object field : List.of(workId, sourceSubmissionId, targetFormVersionId,
                sourceXFormSha256, targetXFormSha256, rules.size())) {
            value.append(field).append('\n');
        }
        for (FieldRule rule : rules) {
            value.append(rule.sourcePath()).append('\t').append(rule.targetPath())
                    .append('\t').append(rule.type().name()).append('\n');
        }
        return value.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public String sha256() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Compare to independently obtained current server identity, not request echoes. */
    public boolean matchesScope(UUID work, UUID source, UUID target, String historicalHash, String targetHash) {
        return workId.equals(work) && sourceSubmissionId.equals(source) &&
                targetFormVersionId.equals(target) && sourceXFormSha256.equals(historicalHash) &&
                targetXFormSha256.equals(targetHash);
    }

    private static boolean nonNil(UUID value) {
        return value != null && !value.equals(new UUID(0, 0));
    }
    private static boolean validHash(String value) {
        return value != null && HASH.matcher(value).matches();
    }
    private static boolean validPath(String value) {
        return value != null && value.length() <= 255 && PATH.matcher(value).matches()
                && java.util.Arrays.stream(value.split("/"))
                    .noneMatch(segment -> RESERVED.contains(segment.toLowerCase(Locale.ROOT)));
    }
}
