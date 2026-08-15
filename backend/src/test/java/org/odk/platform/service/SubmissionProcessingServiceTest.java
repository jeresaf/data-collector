package org.odk.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.javarosa.core.model.FormDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubmissionProcessingServiceTest {

    @Mock
    private SubmissionXmlParser xmlParser;

    @Mock
    private JavaRosaFormLoader formLoader;

    @Mock
    private FormDef formDef;

    private SubmissionProcessingService submissionProcessingService;
    private ObjectMapper objectMapper = new ObjectMapper();

    private static final String VALID_XFORM_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Test Form</h:title>
                    <model>
                        <instance>
                            <data id="test_form" version="1.0.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <name/>
                                <age/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/name" type="string" required="true()"/>
                        <bind nodeset="/data/age" type="int" constraint=". > 0 and . < 120"/>
                    </model>
                </h:head>
                <h:body>
                    <input ref="/data/name">
                        <label>Name</label>
                    </input>
                    <input ref="/data/age">
                        <label>Age</label>
                    </input>
                </h:body>
            </h:html>
            """;

    private static final String SUBMISSION_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <data id="test_form" version="1.0.0">
                <name>John Doe</name>
                <age>30</age>
                <meta>
                    <instanceID>uuid:12345678-1234-1234-1234-123456789012</instanceID>
                </meta>
            </data>
            """;

    private static final String SUBMISSION_XML_WITH_REPEATS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <data id="repeat_form" version="1.0">
                <name>Parent</name>
                <children>
                    <child_name>Child 1</child_name>
                    <child_age>5</child_age>
                </children>
                <children>
                    <child_name>Child 2</child_name>
                    <child_age>3</child_age>
                </children>
                <meta>
                    <instanceID>uuid:ffffffff-ffff-ffff-ffff-ffffffffffff</instanceID>
                </meta>
            </data>
            """;

    @BeforeEach
    void setUp() {
        submissionProcessingService = new SubmissionProcessingService(xmlParser, formLoader);
    }

    // ==================== XML to JSON Conversion Tests ====================

    @Test
    void processSubmission_validXml_returnsProcessedSubmission() {
        // Given
        JsonNode expectedJson = objectMapper.createObjectNode()
                .put("name", "John Doe")
                .put("age", "30")
                .set("meta", objectMapper.createObjectNode().put("instanceID", "uuid:12345678-1234-1234-1234-123456789012"));

        when(xmlParser.parseSubmission(SUBMISSION_XML, formDef)).thenReturn(expectedJson);
        when(xmlParser.extractInstanceId(SUBMISSION_XML)).thenReturn("uuid:12345678-1234-1234-1234-123456789012");

        // When
        SubmissionProcessingService.ProcessedSubmission result = submissionProcessingService.processSubmission(SUBMISSION_XML, formDef);

        // Then
        assertNotNull(result);
        assertEquals(expectedJson, result.getJsonContent());
        assertEquals("uuid:12345678-1234-1234-1234-123456789012", result.getInstanceId());
        assertNotNull(result.getFlattenedAnswers());
        assertNotNull(result.getMetadata());
    }

    @Test
    void processSubmission_xmlToJsonConversion_createsCorrectStructure() {
        // Given
        JsonNode expectedJson = objectMapper.createObjectNode()
                .put("name", "John Doe")
                .put("age", "30")
                .set("meta", objectMapper.createObjectNode().put("instanceID", "uuid:12345678-1234-1234-1234-123456789012"));

        when(xmlParser.parseSubmission(SUBMISSION_XML, formDef)).thenReturn(expectedJson);
        when(xmlParser.extractInstanceId(SUBMISSION_XML)).thenReturn("uuid:12345678-1234-1234-1234-123456789012");

        // When
        SubmissionProcessingService.ProcessedSubmission result = submissionProcessingService.processSubmission(SUBMISSION_XML, formDef);

        // Then
        JsonNode jsonContent = result.getJsonContent();
        assertTrue(jsonContent.has("name"));
        assertTrue(jsonContent.has("age"));
        assertTrue(jsonContent.has("meta"));
        assertEquals("John Doe", jsonContent.get("name").asText());
        assertEquals("30", jsonContent.get("age").asText());
        assertEquals("uuid:12345678-1234-1234-1234-123456789012", jsonContent.get("meta").get("instanceID").asText());
    }

    @Test
    void processSubmission_withFormVersion_returnsProcessedSubmission() {
        // Given
        org.odk.platform.domain.FormVersion formVersion = mock(org.odk.platform.domain.FormVersion.class);
        org.odk.platform.domain.AppUser submittedBy = mock(org.odk.platform.domain.AppUser.class);
        org.odk.platform.domain.XForm form = mock(org.odk.platform.domain.XForm.class);

        when(formVersion.getXmlContent()).thenReturn(VALID_XFORM_XML);
        when(formVersion.getId()).thenReturn(java.util.UUID.randomUUID());
        when(formVersion.getVersion()).thenReturn("1.0.0");
        when(formVersion.getForm()).thenReturn(form);
        when(form.getId()).thenReturn(java.util.UUID.randomUUID());
        when(form.getTitle()).thenReturn("Test Form");
        when(submittedBy.getId()).thenReturn(java.util.UUID.randomUUID());
        when(submittedBy.getUsername()).thenReturn("testuser");
        when(submittedBy.getEmail()).thenReturn("test@example.com");

        JsonNode expectedJson = objectMapper.createObjectNode()
                .put("name", "John Doe")
                .put("age", "30")
                .set("meta", objectMapper.createObjectNode().put("instanceID", "uuid:12345678-1234-1234-1234-123456789012"));

        when(formLoader.loadFormDef(VALID_XFORM_XML)).thenReturn(formDef);
        when(xmlParser.parseSubmission(SUBMISSION_XML, formDef)).thenReturn(expectedJson);
        when(xmlParser.extractInstanceId(SUBMISSION_XML)).thenReturn("uuid:12345678-1234-1234-1234-123456789012");

        // When
        SubmissionProcessingService.ProcessedSubmission result = submissionProcessingService.processSubmission(
                SUBMISSION_XML, formVersion, submittedBy, SUBMISSION_XML);

        // Then
        assertNotNull(result);
        assertEquals(expectedJson, result.getJsonContent());
        assertEquals("uuid:12345678-1234-1234-1234-123456789012", result.getInstanceId());
        
        Map<String, Object> metadata = result.getMetadata();
        assertTrue(metadata.containsKey("formVersionId"));
        assertTrue(metadata.containsKey("formVersion"));
        assertTrue(metadata.containsKey("formId"));
        assertTrue(metadata.containsKey("formName"));
        assertTrue(metadata.containsKey("submittedById"));
        assertTrue(metadata.containsKey("submittedByUsername"));
        assertTrue(metadata.containsKey("submittedByEmail"));
    }

    // ==================== Answer Flattening Tests ====================

    @Test
    void flattenAnswers_simpleObject_returnsFlatMap() {
        // Given
        JsonNode jsonContent = objectMapper.createObjectNode()
                .put("name", "John Doe")
                .put("age", "30");

        // When
        Map<String, Object> flattened = submissionProcessingService.flattenAnswers(jsonContent);

        // Then
        assertEquals(2, flattened.size());
        assertEquals("John Doe", flattened.get("name"));
        assertEquals("30", flattened.get("age"));
    }

    @Test
    void flattenAnswers_nestedObject_returnsFlatMapWithPaths() {
        // Given
        JsonNode jsonContent = objectMapper.createObjectNode()
                .put("name", "John Doe")
                .set("address", objectMapper.createObjectNode()
                        .put("city", "NYC")
                        .put("zip", "10001"));

        // When
        Map<String, Object> flattened = submissionProcessingService.flattenAnswers(jsonContent);

        // Then
        assertEquals(3, flattened.size());
        assertEquals("John Doe", flattened.get("name"));
        assertEquals("NYC", flattened.get("address/city"));
        assertEquals("10001", flattened.get("address/zip"));
    }

    @Test
    void flattenAnswers_array_returnsFlatMapWithIndices() {
        // Given
        JsonNode jsonContent = objectMapper.createObjectNode()
                .put("name", "John Doe")
                .set("children", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode().put("child_name", "Child 1").put("child_age", "5"))
                        .add(objectMapper.createObjectNode().put("child_name", "Child 2").put("child_age", "3")));

        // When
        Map<String, Object> flattened = submissionProcessingService.flattenAnswers(jsonContent);

        // Then
        assertEquals(5, flattened.size());
        assertEquals("John Doe", flattened.get("name"));
        assertEquals("Child 1", flattened.get("children[0]/child_name"));
        assertEquals("5", flattened.get("children[0]/child_age"));
        assertEquals("Child 2", flattened.get("children[1]/child_name"));
        assertEquals("3", flattened.get("children[1]/child_age"));
    }

    @Test
    void flattenAnswers_deeplyNested_returnsFlatMapWithFullPaths() {
        // Given
        JsonNode jsonContent = objectMapper.createObjectNode()
                .set("household", objectMapper.createObjectNode()
                        .put("name", "Smith Family")
                        .set("members", objectMapper.createArrayNode()
                                .add(objectMapper.createObjectNode()
                                        .put("name", "John")
                                        .set("details", objectMapper.createObjectNode()
                                                .put("age", "30")
                                                .put("occupation", "Engineer")))
                                .add(objectMapper.createObjectNode()
                                        .put("name", "Jane")
                                        .set("details", objectMapper.createObjectNode()
                                                .put("age", "28")
                                                .put("occupation", "Doctor")))));

        // When
        Map<String, Object> flattened = submissionProcessingService.flattenAnswers(jsonContent);

        // Then
        assertEquals(7, flattened.size());
        assertEquals("Smith Family", flattened.get("household/name"));
        assertEquals("John", flattened.get("household/members[0]/name"));
        assertEquals("30", flattened.get("household/members[0]/details/age"));
        assertEquals("Engineer", flattened.get("household/members[0]/details/occupation"));
        assertEquals("Jane", flattened.get("household/members[1]/name"));
        assertEquals("28", flattened.get("household/members[1]/details/age"));
        assertEquals("Doctor", flattened.get("household/members[1]/details/occupation"));
    }

    @Test
    void flattenAnswers_emptyObject_returnsEmptyMap() {
        // Given
        JsonNode jsonContent = objectMapper.createObjectNode();

        // When
        Map<String, Object> flattened = submissionProcessingService.flattenAnswers(jsonContent);

        // Then
        assertTrue(flattened.isEmpty());
    }

    @Test
    void flattenAnswers_nullValues_skipsNulls() {
        // Given
        JsonNode jsonContent = objectMapper.createObjectNode()
                .put("name", "John Doe")
                .putNull("age");

        // When
        Map<String, Object> flattened = submissionProcessingService.flattenAnswers(jsonContent);

        // Then
        assertEquals(1, flattened.size());
        assertEquals("John Doe", flattened.get("name"));
        assertFalse(flattened.containsKey("age"));
    }

    // ==================== Repeat Handling Tests ====================

    @Test
    void processSubmission_withRepeats_parsesCorrectly() {
        // Given
        ObjectNode expectedJson = objectMapper.createObjectNode();
        expectedJson.put("name", "Parent");
        ArrayNode childrenArray = objectMapper.createArrayNode();
        childrenArray.add(objectMapper.createObjectNode().put("child_name", "Child 1").put("child_age", "5"));
        childrenArray.add(objectMapper.createObjectNode().put("child_name", "Child 2").put("child_age", "3"));
        expectedJson.set("children", childrenArray);
        expectedJson.set("meta", objectMapper.createObjectNode().put("instanceID", "uuid:ffffffff-ffff-ffff-ffff-ffffffffffff"));

        when(xmlParser.parseSubmission(SUBMISSION_XML_WITH_REPEATS, formDef)).thenReturn(expectedJson);
        when(xmlParser.extractInstanceId(SUBMISSION_XML_WITH_REPEATS)).thenReturn("uuid:ffffffff-ffff-ffff-ffff-ffffffffffff");

        // When
        SubmissionProcessingService.ProcessedSubmission result = submissionProcessingService.processSubmission(SUBMISSION_XML_WITH_REPEATS, formDef);

        // Then
        assertNotNull(result);
        JsonNode jsonContent = result.getJsonContent();
        assertTrue(jsonContent.has("children"));
        assertTrue(jsonContent.get("children").isArray());
        assertEquals(2, jsonContent.get("children").size());
        
        JsonNode firstChild = jsonContent.get("children").get(0);
        assertEquals("Child 1", firstChild.get("child_name").asText());
        assertEquals("5", firstChild.get("child_age").asText());
        
        JsonNode secondChild = jsonContent.get("children").get(1);
        assertEquals("Child 2", secondChild.get("child_name").asText());
        assertEquals("3", secondChild.get("child_age").asText());
    }

    @Test
    void flattenAnswers_withRepeats_createsIndexedPaths() {
        // Given
        JsonNode jsonContent = objectMapper.createObjectNode()
                .put("name", "Parent")
                .set("children", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode().put("child_name", "Child 1").put("child_age", "5"))
                        .add(objectMapper.createObjectNode().put("child_name", "Child 2").put("child_age", "3")));

        // When
        Map<String, Object> flattened = submissionProcessingService.flattenAnswers(jsonContent);

        // Then
        assertEquals(5, flattened.size());
        assertEquals("Parent", flattened.get("name"));
        assertEquals("Child 1", flattened.get("children[0]/child_name"));
        assertEquals("5", flattened.get("children[0]/child_age"));
        assertEquals("Child 2", flattened.get("children[1]/child_name"));
        assertEquals("3", flattened.get("children[1]/child_age"));
    }

    @Test
    void flattenAnswers_nestedRepeats_createsCorrectPaths() {
        // Given - nested repeat structure
        JsonNode jsonContent = objectMapper.createObjectNode()
                .put("household_name", "Smith")
                .set("members", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("name", "John")
                                .set("visits", objectMapper.createArrayNode()
                                        .add(objectMapper.createObjectNode().put("date", "2024-01-15").put("notes", "Checkup"))
                                        .add(objectMapper.createObjectNode().put("date", "2024-02-20").put("notes", "Follow-up"))))
                        .add(objectMapper.createObjectNode()
                                .put("name", "Jane")
                                .set("visits", objectMapper.createArrayNode()
                                        .add(objectMapper.createObjectNode().put("date", "2024-01-10").put("notes", "Initial")))));

        // When
        Map<String, Object> flattened = submissionProcessingService.flattenAnswers(jsonContent);

        // Then - 9 entries: household_name, members[0]/name, members[0]/visits[0]/date, 
        // members[0]/visits[0]/notes, members[0]/visits[1]/date, members[0]/visits[1]/notes,
        // members[1]/name, members[1]/visits[0]/date, members[1]/visits[0]/notes
        assertEquals(9, flattened.size());
        assertEquals("Smith", flattened.get("household_name"));
        assertEquals("John", flattened.get("members[0]/name"));
        assertEquals("2024-01-15", flattened.get("members[0]/visits[0]/date"));
        assertEquals("Checkup", flattened.get("members[0]/visits[0]/notes"));
        assertEquals("2024-02-20", flattened.get("members[0]/visits[1]/date"));
        assertEquals("Follow-up", flattened.get("members[0]/visits[1]/notes"));
        assertEquals("Jane", flattened.get("members[1]/name"));
        assertEquals("2024-01-10", flattened.get("members[1]/visits[0]/date"));
        assertEquals("Initial", flattened.get("members[1]/visits[0]/notes"));
    }

    @Test
    void flattenAnswers_singleRepeatInstance_createsArrayWithOneElement() {
        // Given
        JsonNode jsonContent = objectMapper.createObjectNode()
                .put("name", "Parent")
                .set("children", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode().put("child_name", "Only Child").put("child_age", "7")));

        // When
        Map<String, Object> flattened = submissionProcessingService.flattenAnswers(jsonContent);

        // Then
        assertEquals(3, flattened.size());
        assertEquals("Parent", flattened.get("name"));
        assertEquals("Only Child", flattened.get("children[0]/child_name"));
        assertEquals("7", flattened.get("children[0]/child_age"));
    }

    // ==================== Metadata Extraction Tests ====================

    @Test
    void processSubmission_metadataIncludesSubmittedAt() {
        // Given
        JsonNode expectedJson = objectMapper.createObjectNode()
                .put("name", "John Doe")
                .set("meta", objectMapper.createObjectNode().put("instanceID", "uuid:12345678-1234-1234-1234-123456789012"));

        when(xmlParser.parseSubmission(SUBMISSION_XML, formDef)).thenReturn(expectedJson);
        when(xmlParser.extractInstanceId(SUBMISSION_XML)).thenReturn("uuid:12345678-1234-1234-1234-123456789012");

        // When
        SubmissionProcessingService.ProcessedSubmission result = submissionProcessingService.processSubmission(SUBMISSION_XML, formDef);

        // Then
        Map<String, Object> metadata = result.getMetadata();
        assertTrue(metadata.containsKey("submittedAt"));
        assertTrue(metadata.containsKey("xmlSize"));
        assertTrue(metadata.containsKey("jsonSize"));
        assertTrue(metadata.containsKey("meta.instanceID"));
        assertEquals("uuid:12345678-1234-1234-1234-123456789012", metadata.get("meta.instanceID"));
    }

    @Test
    void processSubmission_metadataIncludesFormVersionInfo() {
        // Given
        org.odk.platform.domain.FormVersion formVersion = mock(org.odk.platform.domain.FormVersion.class);
        org.odk.platform.domain.AppUser submittedBy = mock(org.odk.platform.domain.AppUser.class);
        org.odk.platform.domain.XForm form = mock(org.odk.platform.domain.XForm.class);

        java.util.UUID formVersionId = java.util.UUID.randomUUID();
        java.util.UUID formId = java.util.UUID.randomUUID();
        java.util.UUID submittedById = java.util.UUID.randomUUID();

        when(formVersion.getXmlContent()).thenReturn(VALID_XFORM_XML);
        when(formVersion.getId()).thenReturn(formVersionId);
        when(formVersion.getVersion()).thenReturn("2.0.0");
        when(formVersion.getForm()).thenReturn(form);
        when(form.getId()).thenReturn(formId);
        when(form.getTitle()).thenReturn("Test Form v2");
        when(submittedBy.getId()).thenReturn(submittedById);
        when(submittedBy.getUsername()).thenReturn("collector1");
        when(submittedBy.getEmail()).thenReturn("collector@example.com");

        JsonNode expectedJson = objectMapper.createObjectNode()
                .put("name", "John Doe")
                .set("meta", objectMapper.createObjectNode().put("instanceID", "uuid:12345678-1234-1234-1234-123456789012"));

        when(formLoader.loadFormDef(VALID_XFORM_XML)).thenReturn(formDef);
        when(xmlParser.parseSubmission(SUBMISSION_XML, formDef)).thenReturn(expectedJson);
        when(xmlParser.extractInstanceId(SUBMISSION_XML)).thenReturn("uuid:12345678-1234-1234-1234-123456789012");

        // When
        SubmissionProcessingService.ProcessedSubmission result = submissionProcessingService.processSubmission(
                SUBMISSION_XML, formVersion, submittedBy, SUBMISSION_XML);

        // Then
        Map<String, Object> metadata = result.getMetadata();
        assertEquals(formVersionId.toString(), metadata.get("formVersionId"));
        assertEquals("2.0.0", metadata.get("formVersion"));
        assertEquals(formId.toString(), metadata.get("formId"));
        assertEquals("Test Form v2", metadata.get("formName"));
        assertEquals(submittedById.toString(), metadata.get("submittedById"));
        assertEquals("collector1", metadata.get("submittedByUsername"));
        assertEquals("collector@example.com", metadata.get("submittedByEmail"));
    }

    // ==================== Instance ID Extraction Tests ====================

    @Test
    void processSubmission_extractsInstanceIdFromMeta() {
        // Given
        String submissionWithInstanceId = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_form" version="1.0">
                    <name>Test</name>
                    <meta>
                        <instanceID>uuid:abcdefab-cdef-abcd-efab-cdefabcdefab</instanceID>
                    </meta>
                </data>
                """;

        JsonNode expectedJson = objectMapper.createObjectNode()
                .put("name", "Test")
                .set("meta", objectMapper.createObjectNode().put("instanceID", "uuid:abcdefab-cdef-abcd-efab-cdefabcdefab"));

        when(xmlParser.parseSubmission(submissionWithInstanceId, formDef)).thenReturn(expectedJson);
        when(xmlParser.extractInstanceId(submissionWithInstanceId)).thenReturn("uuid:abcdefab-cdef-abcd-efab-cdefabcdefab");

        // When
        SubmissionProcessingService.ProcessedSubmission result = submissionProcessingService.processSubmission(submissionWithInstanceId, formDef);

        // Then
        assertEquals("uuid:abcdefab-cdef-abcd-efab-cdefabcdefab", result.getInstanceId());
    }

    @Test
    void processSubmission_extractsInstanceIdCaseInsensitive() {
        // Given - lowercase instanceId
        String submissionWithLowercaseId = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_form" version="1.0">
                    <name>Test</name>
                    <meta>
                        <instanceId>uuid:lowercase-id-test</instanceId>
                    </meta>
                </data>
                """;

        JsonNode expectedJson = objectMapper.createObjectNode()
                .put("name", "Test")
                .set("meta", objectMapper.createObjectNode().put("instanceId", "uuid:lowercase-id-test"));

        when(xmlParser.parseSubmission(submissionWithLowercaseId, formDef)).thenReturn(expectedJson);
        when(xmlParser.extractInstanceId(submissionWithLowercaseId)).thenReturn("uuid:lowercase-id-test");

        // When
        SubmissionProcessingService.ProcessedSubmission result = submissionProcessingService.processSubmission(submissionWithLowercaseId, formDef);

        // Then
        assertEquals("uuid:lowercase-id-test", result.getInstanceId());
    }

    // ==================== Edge Cases ====================

    @Test
    void processSubmission_emptySubmission_returnsEmptyJson() {
        // Given
        String emptySubmission = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_form" version="1.0">
                    <meta>
                        <instanceID>uuid:empty-test</instanceID>
                    </meta>
                </data>
                """;

        JsonNode expectedJson = objectMapper.createObjectNode()
                .set("meta", objectMapper.createObjectNode().put("instanceID", "uuid:empty-test"));

        when(xmlParser.parseSubmission(emptySubmission, formDef)).thenReturn(expectedJson);
        when(xmlParser.extractInstanceId(emptySubmission)).thenReturn("uuid:empty-test");

        // When
        SubmissionProcessingService.ProcessedSubmission result = submissionProcessingService.processSubmission(emptySubmission, formDef);

        // Then
        assertNotNull(result);
        // Meta block is flattened, so we expect 1 entry (meta/instanceID)
        assertEquals(1, result.getFlattenedAnswers().size());
        assertEquals("uuid:empty-test", result.getFlattenedAnswers().get("meta/instanceID"));
        assertEquals("uuid:empty-test", result.getInstanceId());
    }

    @Test
    void processSubmission_specialCharactersInValues_handlesCorrectly() {
        // Given
        String submissionWithSpecialChars = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_form" version="1.0">
                    <name>John & Jane</name>
                    <notes>Special chars: <>"'&</notes>
                    <meta>
                        <instanceID>uuid:special-chars-test</instanceID>
                    </meta>
                </data>
                """;

        JsonNode expectedJson = objectMapper.createObjectNode()
                .put("name", "John & Jane")
                .put("notes", "Special chars: <>\"'&")
                .set("meta", objectMapper.createObjectNode().put("instanceID", "uuid:special-chars-test"));

        when(xmlParser.parseSubmission(submissionWithSpecialChars, formDef)).thenReturn(expectedJson);
        when(xmlParser.extractInstanceId(submissionWithSpecialChars)).thenReturn("uuid:special-chars-test");

        // When
        SubmissionProcessingService.ProcessedSubmission result = submissionProcessingService.processSubmission(submissionWithSpecialChars, formDef);

        // Then
        assertEquals("John & Jane", result.getJsonContent().get("name").asText());
        assertEquals("Special chars: <>\"'&", result.getJsonContent().get("notes").asText());
    }

    @Test
    void processSubmission_unicodeValues_handlesCorrectly() {
        // Given
        String submissionWithUnicode = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="test_form" version="1.0">
                    <name>张三</name>
                    <city>Москва</city>
                    <emoji>🎉🌟</emoji>
                    <meta>
                        <instanceID>uuid:unicode-test</instanceID>
                    </meta>
                </data>
                """;

        JsonNode expectedJson = objectMapper.createObjectNode()
                .put("name", "张三")
                .put("city", "Москва")
                .put("emoji", "🎉🌟")
                .set("meta", objectMapper.createObjectNode().put("instanceID", "uuid:unicode-test"));

        when(xmlParser.parseSubmission(submissionWithUnicode, formDef)).thenReturn(expectedJson);
        when(xmlParser.extractInstanceId(submissionWithUnicode)).thenReturn("uuid:unicode-test");

        // When
        SubmissionProcessingService.ProcessedSubmission result = submissionProcessingService.processSubmission(submissionWithUnicode, formDef);

        // Then
        assertEquals("张三", result.getJsonContent().get("name").asText());
        assertEquals("Москва", result.getJsonContent().get("city").asText());
        assertEquals("🎉🌟", result.getJsonContent().get("emoji").asText());
    }
}