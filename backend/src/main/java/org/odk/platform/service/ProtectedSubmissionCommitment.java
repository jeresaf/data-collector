package org.odk.platform.service;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Versioned digest contract for the protected submission lifecycle.
 * Not an admission decision, retry token, or evidence of a committed write.
 * Deliberately not connected to the legacy intake until the durable admission
 * transaction and matching Android consumer have been qualified.
 */
public final class ProtectedSubmissionCommitment {
    private static final String XML = "xml_submission_file";
    private static final int MAX_XML = 8 * 1024 * 1024;
    private static final int MAX_MEDIA = 1_500_000;
    private static final long MAX_TOTAL = 12L * 1024 * 1024;
    private static final int MAX_PARTS = 64;

    private ProtectedSubmissionCommitment() {}

    public record Binding(UUID projectId, UUID actorId, UUID workId, UUID sourceId,
                          UUID targetVersionId, UUID attemptId, String instanceId,
                          RecordWorkService.WorkType workType) {
        public Binding {
            Objects.requireNonNull(projectId);
            Objects.requireNonNull(actorId);
            Objects.requireNonNull(workId);
            Objects.requireNonNull(sourceId);
            Objects.requireNonNull(targetVersionId);
            Objects.requireNonNull(attemptId);
            Objects.requireNonNull(workType);
            require(instanceId != null && instanceId.startsWith("uuid:"));
            String id = instanceId.substring(5);
            require(UUID.fromString(id).toString().equals(id));
        }
    }

    private record Part(String filename, String mime, long bytes, String sha256) {}

    /**
     * Compute from actual multipart bytes, not client-provided hash headers.
     * XML is exact strict UTF-8; no normalization. Media order and multipart
     * boundary are irrelevant, while names, MIME, byte count and digest bind.
     * Only the current protected client's canonical part profile is admitted.
     * No payload bytes, credentials, or storage paths are retained by this method.
     */
    public static String fromMultipart(Binding binding, MultiValueMap<String, MultipartFile> files)
            throws IOException {
        Objects.requireNonNull(binding);
        require(files != null && files.size() >= 1 && files.size() <= MAX_PARTS + 1);
        List<MultipartFile> xmlParts = files.get(XML);
        require(xmlParts != null && xmlParts.size() == 1);
        MultipartFile xml = xmlParts.get(0);
        require(xml != null && XML.equals(xml.getName()) &&
                "submission.xml".equals(xml.getOriginalFilename()) &&
                "text/xml".equals(xml.getContentType()));
        long xmlSize = xml.getSize();
        require(xmlSize > 0 && xmlSize <= MAX_XML);
        byte[] xmlBytes;
        try (InputStream in = xml.getInputStream()) {
            xmlBytes = in.readNBytes((int) xmlSize + 1);
        }
        String xmlHash;
        try {
            require(xmlBytes.length == xmlSize);
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(xmlBytes));
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException("Invalid protected XML encoding");
            }
            xmlHash = HexFormat.of().formatHex(digest().digest(xmlBytes));
        } finally {
            java.util.Arrays.fill(xmlBytes, (byte) 0);
        }
        List<Part> parts = new ArrayList<>();
        long total = xmlSize;
        for (var entry : files.entrySet()) {
            String name = entry.getKey();
            if (XML.equals(name)) continue;
            require(name != null && name.matches("[A-Za-z0-9._-]{1,255}") && !name.contains(".."));
            List<MultipartFile> list = entry.getValue();
            require(list != null && list.size() == 1);
            MultipartFile part = list.get(0);
            require(part != null && name.equals(part.getName()) && name.equals(part.getOriginalFilename()));
            String mime = part.getContentType();
            require("image/jpeg".equals(mime) || "image/png".equals(mime));
            long size = part.getSize();
            require(size > 0 && size <= MAX_MEDIA);
            total += size;
            require(total <= MAX_TOTAL);
            parts.add(new Part(name, mime, size, hashBounded(part, size)));
        }
        parts.sort(Comparator.comparing(Part::filename));
        MessageDigest commitment = digest();
        // Every field is UTF-8 prefixed by a four-byte big-endian byte length.
        // Decimal counts are canonical (no leading zeros). No writeUTF/MUTF-8.
        for (String value : List.of("DC_PROTECTED_WIRE_V1", binding.projectId().toString(),
                binding.actorId().toString(), binding.workId().toString(),
                binding.sourceId().toString(), binding.targetVersionId().toString(),
                binding.attemptId().toString(), binding.instanceId(), binding.workType().name(),
                Long.toString(xmlSize), xmlHash, Integer.toString(parts.size()))) {
            frame(commitment, value);
        }
        for (Part part : parts) {
            frame(commitment, part.filename());
            frame(commitment, part.mime());
            frame(commitment, Long.toString(part.bytes()));
            frame(commitment, part.sha256());
        }
        return HexFormat.of().formatHex(commitment.digest());
    }

    private static String hashBounded(MultipartFile part, long expected) throws IOException {
        MessageDigest hash = digest();
        byte[] buffer = new byte[8192];
        long count = 0;
        try (InputStream in = part.getInputStream()) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                count += n;
                require(count <= expected);
                hash.update(buffer, 0, n);
            }
            require(count == expected);
            return HexFormat.of().formatHex(hash.digest());
        } finally {
            java.util.Arrays.fill(buffer, (byte) 0);
        }
    }

    private static void frame(MessageDigest hash, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        hash.update(bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid protected submission commitment");
    }
}
