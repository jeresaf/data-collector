package org.odk.platform.openrosa;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Defensive qualification for explicitly declared ODK Entity List CSV media.
 *
 * <p>Neither method publishes media, authorizes requests, nor changes a
 * manifest. Callers must independently validate form/project assignment,
 * active dataset entitlement, the exact hash of served bytes, and client
 * interoperability before advertising a typed media file.</p>
 */
public final class EntityListManifestPreflight {
    public static final String METADATA_KEY = "odkMediaType";

    private static final Set<String> TYPES = Set.of("entityList", "approvalEntityList");
    private static final Set<String> REQUIRED_HEADERS = Set.of("name", "label", "__version");
    private static final int MAX_COLUMNS = 256;

    private EntityListManifestPreflight() {
    }

    /**
     * Verifies explicit metadata and a minimal safe header. This result is
     * deliberately NOT sufficient to emit a typed manifest entry.
     */
    public static Optional<String> declaredCandidate(Map<String, Object> metadata, byte[] csvBytes) {
        Optional<String> type = declaredType(metadata);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        Optional<List<List<String>>> parsed = parse(csvBytes);
        if (parsed.isEmpty() || parsed.get().size() < 2 || !validHeader(parsed.get().get(0))
                || containsQuotedHeader(csvBytes)) {
            return Optional.empty();
        }
        return type;
    }

    /**
     * Stricter all-row check for a typed publishing candidate. This does not
     * replace the authorization, digest, ODK parser and deletion-semantics gates.
     */
    public static Optional<String> validatedCandidate(Map<String, Object> metadata, byte[] csvBytes) {
        Optional<String> type = declaredType(metadata);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        Optional<List<List<String>>> parsed = parse(csvBytes);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        List<List<String>> rows = parsed.get();
        List<String> header = rows.get(0);
        if (!validHeader(header) || rows.size() < 2 || containsQuotedHeader(csvBytes)) {
            return Optional.empty();
        }

        int nameIndex = header.indexOf("name");
        int versionIndex = header.indexOf("__version");
        Set<UUID> seenIds = new HashSet<>();

        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() != header.size()) {
                return Optional.empty();
            }
            String name = row.get(nameIndex);
            if (!name.equals(name.trim()) || name.isBlank()) {
                return Optional.empty();
            }
            UUID id;
            try {
                id = UUID.fromString(name);
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
            // UUID.fromString historically accepted short noncanonical forms.
            if (!id.toString().equalsIgnoreCase(name) || !seenIds.add(id)) {
                return Optional.empty();
            }
            String version = row.get(versionIndex);
            if (!version.matches("[1-9][0-9]*")) {
                return Optional.empty();
            }
            try {
                Integer.parseInt(version);
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return type;
    }

    /**
     * The last local eligibility gate before manifest publication: matches
     * the recorded MD5 against the exact byte payload supplied by storage.
     *
     * <p>This does not fetch storage itself, prevent subsequent mutation, or
     * grant publication; the caller still needs snapshot/stream stability,
     * authorization and Android importer qualification.</p>
     */
    public static Optional<String> verifiedCandidate(
            Map<String, Object> metadata, byte[] csvBytes, String recordedHash) {
        Optional<String> valid = validatedCandidate(metadata, csvBytes);
        if (valid.isEmpty() || recordedHash == null) {
            return Optional.empty();
        }

        String digest = recordedHash.regionMatches(true, 0, "md5:", 0, 4)
                ? recordedHash.substring(4) : recordedHash;
        if (!digest.matches("(?i)[a-f0-9]{32}")) {
            return Optional.empty();
        }
        try {
            byte[] actual = MessageDigest.getInstance("MD5").digest(csvBytes);
            String actualHex = HexFormat.of().formatHex(actual);
            return MessageDigest.isEqual(
                    actualHex.getBytes(StandardCharsets.US_ASCII),
                    digest.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII))
                    ? valid : Optional.empty();
        } catch (NoSuchAlgorithmException e) {
            // A missing digest algorithm is not authorization to publish.
            return Optional.empty();
        }
    }

    /**
     * Collect derives the local list name from filename.substringBefore(".csv").
     * Allow one canonical lowercase extension and a stable, unambiguous base.
     * This is deliberately stricter than ordinary form-media filenames.
     */
    public static boolean validTypedFilename(String filename) {
        return filename != null && filename.length() <= 255
                && filename.matches("[A-Za-z0-9][A-Za-z0-9_-]*\\.csv");
    }

    private static boolean containsQuotedHeader(byte[] csvBytes) {
        // Reject even correctly quoted header identifiers to preserve the
        // initial conservative allowlist until native ODK parser parity is proven.
        for (byte b : csvBytes) {
            if (b == '\n' || b == '\r') {
                break;
            }
            if (b == '"') {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> declaredType(Map<String, Object> metadata) {
        if (metadata == null) {
            return Optional.empty();
        }
        Object type = metadata.get(METADATA_KEY);
        return type instanceof String declared && TYPES.contains(declared)
                ? Optional.of(declared) : Optional.empty();
    }

    private static boolean validHeader(List<String> header) {
        return header.size() <= MAX_COLUMNS
                && new HashSet<>(header).size() == header.size()
                && header.containsAll(REQUIRED_HEADERS)
                && header.stream().allMatch(name -> name.matches("[A-Za-z_][A-Za-z0-9_.-]*"));
    }

    /**
     * RFC4180-style quoted CSV fields, including escaped quotes and newlines.
     * Rejects malformed quotes, inconsistent UTF-8, binary NUL, blank records,
     * empty files, and ambiguous trailing separators.
     */
    private static Optional<List<List<String>>> parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        final String input;
        try {
            input = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
        if (input.indexOf('\0') >= 0 || input.startsWith("\uFEFF")) {
            return Optional.empty();
        }

        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean afterQuote = false;
        boolean atFieldStart = true;
        boolean atRecordStart = true;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                        afterQuote = true;
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }
            if (afterQuote && ch != ',' && ch != '\n' && ch != '\r') {
                return Optional.empty();
            }
            if (ch == '"') {
                if (!atFieldStart || afterQuote) {
                    return Optional.empty();
                }
                quoted = true;
                atFieldStart = false;
                atRecordStart = false;
            } else if (ch == ',') {
                row.add(field.toString());
                field.setLength(0);
                afterQuote = false;
                atFieldStart = true;
                atRecordStart = false;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                if (row.size() > MAX_COLUMNS || atRecordStart) {
                    return Optional.empty();
                }
                rows.add(List.copyOf(row));
                row.clear();
                field.setLength(0);
                afterQuote = false;
                atFieldStart = true;
                atRecordStart = true;
            } else {
                field.append(ch);
                atFieldStart = false;
                atRecordStart = false;
            }
        }
        if (quoted) {
            return Optional.empty();
        }
        if (!atRecordStart) {
            row.add(field.toString());
            if (row.size() > MAX_COLUMNS) {
                return Optional.empty();
            }
            rows.add(List.copyOf(row));
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows);
    }
}
