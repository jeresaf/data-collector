package org.odk.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javarosa.core.model.FormDef;
import org.odk.platform.domain.FormVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Service for processing form submissions - XML to JSON conversion, 
 * answer flattening, and metadata extraction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubmissionProcessingService {

    private final SubmissionXmlParser xmlParser;
    private final JavaRosaFormLoader formLoader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Process a submission: parse XML, convert to JSON, flatten answers, extract metadata.
     * 
     * @param xmlContent The raw submission XML
     * @param formDef The form definition
     * @return Processed submission data containing JSON content, flattened answers, and metadata
     */
    @Transactional
    public ProcessedSubmission processSubmission(String xmlContent, FormDef formDef) {
        log.info("Processing submission with formDef: {}", formDef.getName() != null ? formDef.getName() : "unknown");

        // Parse XML to nested JSON
        JsonNode jsonContent = xmlParser.parseSubmission(xmlContent, formDef);
        
        // Flatten answers for easy querying
        Map<String, Object> flattenedAnswers = flattenAnswers(jsonContent);
        
        // Extract metadata
        Map<String, Object> metadata = extractMetadata(jsonContent, xmlContent);
        
        // Extract instance ID
        String instanceId = xmlParser.extractInstanceId(xmlContent);
        
        return new ProcessedSubmission(
            jsonContent,
            flattenedAnswers,
            metadata,
            instanceId
        );
    }

    /**
     * Process a submission with FormVersion context.
     * 
     * @param xmlContent The raw submission XML
     * @param formVersion The form version entity
     * @param submittedBy The user who submitted
     * @param xmlContentRaw The raw XML content (for metadata)
     * @return Processed submission data containing JSON content, flattened answers, and metadata
     */
    @Transactional
    public ProcessedSubmission processSubmission(String xmlContent, FormVersion formVersion, 
                                                  org.odk.platform.domain.AppUser submittedBy, 
                                                  String xmlContentRaw) {
        log.info("Processing submission for form version: {}", formVersion.getId());

        // Load form definition from the form version's XML content
        FormDef formDef = formLoader.loadFormDef(formVersion.getXmlContent());
        if (formDef == null) {
            throw new IllegalStateException("Failed to load form definition for form version: " + formVersion.getId());
        }

        // Parse XML to nested JSON
        JsonNode jsonContent = xmlParser.parseSubmission(xmlContent, formDef);
        
        // Flatten answers for easy querying
        Map<String, Object> flattenedAnswers = flattenAnswers(jsonContent);
        
        // Extract metadata with form version context
        Map<String, Object> metadata = extractMetadata(jsonContent, xmlContentRaw, formVersion, submittedBy);
        
        // Extract instance ID
        String instanceId = xmlParser.extractInstanceId(xmlContent);
        
        return new ProcessedSubmission(
            jsonContent,
            flattenedAnswers,
            metadata,
            instanceId
        );
    }

    /**
     * Build processed submission data from a JavaRosa-validated instance. This
     * avoids parsing a production OpenRosa submission twice after the strict
     * validation path has already produced the canonical instance tree.
     */
    public ProcessedSubmission processValidatedSubmission(
            JsonNode jsonContent,
            String xmlContent,
            FormVersion formVersion,
            org.odk.platform.domain.AppUser submittedBy,
            String instanceId) {
        if (jsonContent == null) {
            throw new IllegalArgumentException("Validated submission JSON is required");
        }
        Map<String, Object> flattenedAnswers = flattenAnswers(jsonContent);
        Map<String, Object> metadata = extractMetadata(
                jsonContent, xmlContent, formVersion, submittedBy);
        return new ProcessedSubmission(
                jsonContent, flattenedAnswers, metadata, instanceId);
    }

    /**
     * Flatten nested JSON answers into a flat map with XPath-like keys.
     * Example: {"data": {"name": "John", "address": {"city": "NYC"}}} 
     * becomes {"data/name": "John", "data/address/city": "NYC"}
     */
    public Map<String, Object> flattenAnswers(JsonNode jsonContent) {
        Map<String, Object> flattened = new LinkedHashMap<>();
        flattenNode(jsonContent, "", flattened);
        return flattened;
    }

    private void flattenNode(JsonNode node, String path, Map<String, Object> result) {
        if (node == null || node.isNull()) {
            // Skip null values
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String newPath = path.isEmpty() ? field.getKey() : path + "/" + field.getKey();
                flattenNode(field.getValue(), newPath, result);
            }
        } else if (node.isArray()) {
            // For arrays, include index in path
            for (int i = 0; i < node.size(); i++) {
                String newPath = path + "[" + i + "]";
                flattenNode(node.get(i), newPath, result);
            }
        } else {
            // Leaf node - add to result
            result.put(path, node.asText());
        }
    }

    /**
     * Extract metadata from submission JSON and XML.
     */
    private Map<String, Object> extractMetadata(JsonNode jsonContent, String xmlContent) {
        Map<String, Object> metadata = new HashMap<>();
        
        // Add submission timestamp
        metadata.put("submittedAt", Instant.now().toString());
        
        // Extract meta block if present
        if (jsonContent.has("meta")) {
            JsonNode meta = jsonContent.get("meta");
            if (meta.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = meta.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    metadata.put("meta." + field.getKey(), field.getValue().asText());
                }
            }
        }
        
        // Add XML size
        metadata.put("xmlSize", xmlContent.length());
        
        // Add JSON size (approximate)
        metadata.put("jsonSize", jsonContent.toString().length());
        
        return metadata;
    }

    /**
     * Extract metadata from submission JSON and XML with FormVersion context.
     */
    private Map<String, Object> extractMetadata(JsonNode jsonContent, String xmlContent, 
                                                 FormVersion formVersion, 
                                                 org.odk.platform.domain.AppUser submittedBy) {
        Map<String, Object> metadata = extractMetadata(jsonContent, xmlContent);
        
        // Add form version info
        metadata.put("formVersionId", formVersion.getId().toString());
        metadata.put("formVersion", formVersion.getVersion());
        metadata.put("formId", formVersion.getForm().getId().toString());
        metadata.put("formName", formVersion.getForm().getTitle());
        
        // Add submitted by info
        if (submittedBy != null) {
            metadata.put("submittedById", submittedBy.getId().toString());
            metadata.put("submittedByUsername", submittedBy.getUsername());
            metadata.put("submittedByEmail", submittedBy.getEmail());
        }
        
        return metadata;
    }

    /**
     * Result class for processed submission data.
     */
    public static class ProcessedSubmission {
        private final JsonNode jsonContent;
        private final Map<String, Object> flattenedAnswers;
        private final Map<String, Object> metadata;
        private final String instanceId;

        public ProcessedSubmission(JsonNode jsonContent, Map<String, Object> flattenedAnswers,
                                   Map<String, Object> metadata, String instanceId) {
            this.jsonContent = jsonContent;
            this.flattenedAnswers = flattenedAnswers;
            this.metadata = metadata;
            this.instanceId = instanceId;
        }

        public JsonNode getJsonContent() { return jsonContent; }
        public Map<String, Object> getFlattenedAnswers() { return flattenedAnswers; }
        public Map<String, Object> getMetadata() { return metadata; }
        public String getInstanceId() { return instanceId; }
    }
}
