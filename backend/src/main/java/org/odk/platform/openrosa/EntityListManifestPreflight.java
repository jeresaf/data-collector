package org.odk.platform.openrosa;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A conservative, non-authoritative preflight for explicitly declared ODK Entity List media.
 *
 * <p>Passing this check does NOT authorize adding a type attribute to an
 * OpenRosa manifest. The full CSV rows, served-byte digest, project/form
 * authorization, ID/version semantics and update/deletion behavior must be
 * validated independently before publishing a typed entry.</p>
 *
 * <p>Ordinary CSV uploads are never auto-classified by their extension.</p>
 */
public final class EntityListManifestPreflight {
    public static final String METADATA_KEY = "odkMediaType";
    private static final Set<String> TYPES = Set.of("entityList", "approvalEntityList");
    private static final Set<String> REQUIRED_HEADERS = Set.of("name", "label", "__version");

    private EntityListManifestPreflight() {
    }

    /**
     * Returns the explicit declared type only if the minimally required header
     * structure is present. The result is a candidate, NOT publication approval.
     */
    public static Optional<String> declaredCandidate(Map<String, Object> metadata, byte[] csvBytes) {
        if (metadata == null || csvBytes == null || csvBytes.length == 0) {
            return Optional.empty();
        }
        Object type = metadata.get(METADATA_KEY);
        if (!(type instanceof String declaration) || !TYPES.contains(declaration)) {
            return Optional.empty();
        }
        // Reject binary/ambiguous byte sequences and header quotation, rather than
        // risk misclassifying an arbitrary form attachment as an Entity List.
        String text = new String(csvBytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(text.getBytes(StandardCharsets.UTF_8), csvBytes) || text.indexOf('\0') >= 0) {
            return Optional.empty();
        }
        int newline = text.indexOf('\n');
        String header = newline < 0 ? text : text.substring(0, newline);
        if (header.endsWith("\r")) {
            header = header.substring(0, header.length() - 1);
        }
        if (header.isBlank() || header.indexOf('"') >= 0) {
            return Optional.empty();
        }
        String[] columns = header.split(",", -1);
        Set<String> names = Arrays.stream(columns).map(String::trim).collect(Collectors.toSet());
        if (names.size() != columns.length || !names.containsAll(REQUIRED_HEADERS)) {
            return Optional.empty();
        }
        // A header alone is not a dataset; full content validation remains a
        // separate mandatory step before the manifest is ever typed.
        if (newline < 0 || text.substring(newline + 1).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(declaration);
    }
}
