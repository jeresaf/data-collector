package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.javarosa.core.model.FormDef;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.SubmissionAnswer;
import org.odk.platform.domain.SubmissionEvent;
import org.odk.platform.repository.SubmissionAnswerRepository;
import org.odk.platform.repository.SubmissionEventRepository;
import org.odk.platform.repository.SubmissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Persists the normalized representation produced from a validated submission. */
@Service
@RequiredArgsConstructor
public class SubmissionPersistenceService {

    private static final Pattern REPEAT_SEGMENT = Pattern.compile("([^/]+)\\[(\\d+)]");

    private final SubmissionRepository submissionRepository;
    private final SubmissionAnswerRepository answerRepository;
    private final SubmissionEventRepository eventRepository;
    private final FormMetadataExtractor metadataExtractor;

    /**
     * Persist JSON, normalized answer rows and the initial submission event.
     * The Submission must already have an id so answer/event foreign keys are
     * concrete and reporting can see the same transaction atomically.
     */
    @Transactional
    public Submission persistProcessedData(
            Submission submission,
            SubmissionProcessingService.ProcessedSubmission processed,
            FormDef formDef,
            String sourceClient,
            String eventType,
            String actorType,
            UUID actorId,
            Map<String, Object> eventDetails) {

        if (submission == null || submission.getId() == null) {
            throw new IllegalArgumentException("A persisted submission is required");
        }
        if (processed == null) {
            throw new IllegalArgumentException("Processed submission data is required");
        }

        submission.setJsonContent(processed.getJsonContent().toString());
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (submission.getMetadata() != null) metadata.putAll(submission.getMetadata());
        if (processed.getMetadata() != null) metadata.putAll(processed.getMetadata());
        if (sourceClient != null && !sourceClient.isBlank()) {
            metadata.put("sourceClient", sourceClient);
        }
        submission.setMetadata(metadata);
        Submission saved = submissionRepository.saveAndFlush(submission);

        Map<String, FormMetadataExtractor.FieldInfo> fields = fieldIndex(formDef);
        List<SubmissionAnswer> answers = new ArrayList<>();
        for (Map.Entry<String, Object> entry : processed.getFlattenedAnswers().entrySet()) {
            answers.add(toAnswer(saved, entry.getKey(), entry.getValue(), fields));
        }
        if (!answers.isEmpty()) answerRepository.saveAllAndFlush(answers);

        Map<String, Object> details = new LinkedHashMap<>();
        if (eventDetails != null) details.putAll(eventDetails);
        details.putIfAbsent("sourceClient", sourceClient);
        details.put("answerCount", answers.size());

        eventRepository.save(SubmissionEvent.builder()
                .submission(saved)
                .eventType(eventType == null ? "RECEIVED" : eventType)
                .eventStatus("success")
                .actorType(actorType)
                .actorId(actorId)
                .details(details)
                .build());

        return saved;
    }

    private Map<String, FormMetadataExtractor.FieldInfo> fieldIndex(FormDef formDef) {
        Map<String, FormMetadataExtractor.FieldInfo> fields = new HashMap<>();
        if (formDef == null) return fields;
        try {
            FormMetadataExtractor.FormMetadata metadata = metadataExtractor.extractMetadata(formDef);
            if (metadata.getFields() != null) {
                for (FormMetadataExtractor.FieldInfo field : metadata.getFields()) {
                    if (field == null || field.getXpath() == null) continue;
                    fields.put(normalizeDefinitionPath(field.getXpath()), field);
                }
            }
        } catch (RuntimeException ignored) {
            // Normalized answer persistence must not be lost only because optional
            // presentation metadata cannot be extracted. Values fall back to text.
        }
        return fields;
    }

    private SubmissionAnswer toAnswer(
            Submission submission,
            String path,
            Object rawValue,
            Map<String, FormMetadataExtractor.FieldInfo> fields) {

        String value = rawValue == null ? null : String.valueOf(rawValue);
        String normalizedPath = normalizeAnswerPath(path);
        FormMetadataExtractor.FieldInfo field = fields.get(normalizedPath);
        String type = field == null ? null : field.getType();
        RepeatInfo repeat = repeatInfo(path);

        SubmissionAnswer answer = SubmissionAnswer.builder()
                .submission(submission)
                .path(path)
                .fieldName(field != null && field.getName() != null
                        ? field.getName()
                        : lastSegment(path))
                .fieldType(type)
                .repeatPath(repeat.path())
                .repeatIndex(repeat.index())
                .build();

        assignTypedValue(answer, type, value);
        return answer;
    }

    private void assignTypedValue(SubmissionAnswer answer, String fieldType, String value) {
        if (value == null) return;
        String type = fieldType == null ? "" : fieldType.toLowerCase(Locale.ROOT);
        try {
            switch (type) {
                case "int", "integer", "decimal", "double" ->
                        answer.setValueNumber(new BigDecimal(value));
                case "boolean", "bool" ->
                        answer.setValueBoolean(parseBoolean(value));
                case "datetime", "date-time" ->
                        answer.setValueDate(parseDateTime(value));
                case "date" ->
                        answer.setValueDate(LocalDate.parse(value).atStartOfDay().atOffset(ZoneOffset.UTC));
                default -> answer.setValueText(value);
            }
        } catch (RuntimeException ex) {
            // Preserve the submitted value even if a non-standard lexical form
            // cannot be converted to the typed reporting column.
            answer.setValueText(value);
        }
    }

    private Boolean parseBoolean(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("1".equals(normalized) || "true".equals(normalized)) return true;
        if ("0".equals(normalized) || "false".equals(normalized)) return false;
        throw new IllegalArgumentException("Unsupported boolean lexical value");
    }

    private OffsetDateTime parseDateTime(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException ignored) {
            try {
                return Instant.parse(value).atOffset(ZoneOffset.UTC);
            } catch (RuntimeException ignoredAgain) {
                return LocalDateTime.parse(value).atOffset(ZoneOffset.UTC);
            }
        }
    }

    private String normalizeDefinitionPath(String xpath) {
        if (xpath == null) return "";
        String normalized = xpath.trim();
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        int slash = normalized.indexOf('/');
        if (slash >= 0) normalized = normalized.substring(slash + 1);
        return normalizeAnswerPath(normalized);
    }

    private String normalizeAnswerPath(String path) {
        if (path == null) return "";
        return path.replaceAll("\\[\\d+\\]", "");
    }

    private String lastSegment(String path) {
        if (path == null || path.isBlank()) return "unknown";
        String segment = path.substring(path.lastIndexOf('/') + 1);
        return segment.replaceAll("\\[\\d+\\]", "");
    }

    private RepeatInfo repeatInfo(String path) {
        if (path == null) return new RepeatInfo(null, null);
        Matcher matcher = REPEAT_SEGMENT.matcher(path);
        String repeatPath = null;
        Integer repeatIndex = null;
        while (matcher.find()) {
            repeatIndex = Integer.valueOf(matcher.group(2));
            repeatPath = path.substring(0, matcher.start(1)) + matcher.group(1);
        }
        return new RepeatInfo(repeatPath, repeatIndex);
    }

    private record RepeatInfo(String path, Integer index) {
    }
}
