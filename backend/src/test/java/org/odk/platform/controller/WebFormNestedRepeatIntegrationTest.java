package org.odk.platform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javarosa.form.api.FormEntryController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.User;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.SubmissionRepository;
import org.odk.platform.repository.UserRepository;
import org.odk.platform.repository.XFormRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-level regression coverage for the complete nested-repeat web-form flow:
 * start -> navigate -> answer exact JavaRosa instances -> finalize -> persisted XML.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WebFormNestedRepeatIntegrationTest {

    private static final String NESTED_REPEAT_XFORM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms"
                    xmlns:jr="http://openrosa.org/javarosa">
                <h:head>
                    <h:title>Nested Repeat API Form</h:title>
                    <model>
                        <instance>
                            <data id="nested_repeat_api" version="1.0">
                                <member_count>2</member_count>
                                <visit_count>2</visit_count>
                                <members>
                                    <member jr:template="">
                                        <name/>
                                        <visits>
                                            <visit jr:template="">
                                                <note/>
                                            </visit>
                                        </visits>
                                    </member>
                                </members>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/member_count" type="int"/>
                        <bind nodeset="/data/visit_count" type="int"/>
                        <bind nodeset="/data/members/member/name" type="string"/>
                        <bind nodeset="/data/members/member/visits/visit/note" type="string"/>
                    </model>
                </h:head>
                <h:body>
                    <group ref="/data/members">
                        <label>Members</label>
                        <repeat nodeset="/data/members/member" jr:count="/data/member_count">
                            <input ref="/data/members/member/name">
                                <label>Member name</label>
                            </input>
                            <group ref="/data/members/member/visits">
                                <label>Visits</label>
                                <repeat nodeset="/data/members/member/visits/visit" jr:count="/data/visit_count">
                                    <input ref="/data/members/member/visits/visit/note">
                                        <label>Visit note</label>
                                    </input>
                                </repeat>
                            </group>
                        </repeat>
                    </group>
                </h:body>
            </h:html>
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private XFormRepository xFormRepository;

    @Autowired
    private FormVersionRepository formVersionRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    private UUID userId;
    private UUID formVersionId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("nested-repeat-user");
        user.setEmail("nested-repeat@example.com");
        user.setPasswordHash("hashed");
        user.setIsActive(true);
        user = userRepository.save(user);
        userId = user.getId();

        Project project = new Project();
        project.setName("Nested Repeat Project");
        project.setOwner(user);
        project.setCreatedBy(user);
        project = projectRepository.save(project);

        XForm xForm = new XForm();
        xForm.setTitle("Nested Repeat API Form");
        xForm.setXmlns("http://example.org/nested_repeat_api");
        xForm.setVersion("1.0");
        xForm.setXmlContent(NESTED_REPEAT_XFORM);
        xForm.setProject(project);
        xForm.setCreatedBy(user);
        xForm = xFormRepository.save(xForm);

        FormVersion formVersion = new FormVersion();
        formVersion.setForm(xForm);
        formVersion.setXmlContent(NESTED_REPEAT_XFORM);
        formVersion.setVersion("1.0");
        formVersion.setCreatedBy(user);
        formVersion = formVersionRepository.save(formVersion);
        formVersionId = formVersion.getId();
    }

    @Test
    @WithMockUser(username = "nested-repeat-user", roles = {"USER"})
    void nestedRepeats_roundTripThroughApiAndPersistEveryInstance() throws Exception {
        Map<String, Object> startRequest = Map.of("userId", userId.toString());

        MvcResult startResult = mockMvc.perform(
                        post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(startRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").exists())
                .andReturn();

        JsonNode state = objectMapper.readTree(startResult.getResponse().getContentAsString());
        UUID sessionId = UUID.fromString(state.path("sessionId").asText());

        List<String> noteValues = new ArrayList<>();
        Set<String> noteInstancePaths = new LinkedHashSet<>();
        int memberNumber = 0;
        int safetyCounter = 0;

        while (eventOf(state) != FormEntryController.EVENT_END_OF_FORM && safetyCounter++ < 100) {
            if (eventOf(state) == FormEntryController.EVENT_QUESTION) {
                JsonNode question = state.path("question");
                assertTrue(question.isObject(), "Question event must include question metadata");

                String questionName = question.path("name").asText();
                String value;
                if ("name".equals(questionName)) {
                    value = "Member " + (++memberNumber);
                } else if ("note".equals(questionName)) {
                    value = "Visit note " + (noteValues.size() + 1);
                    noteValues.add(value);

                    String instancePath = question.path("instancePath").asText();
                    assertTrue(!instancePath.isBlank(), "Nested repeat questions require an exact instancePath");
                    assertTrue(noteInstancePaths.add(instancePath),
                            "Each nested repeat question must have a distinct JavaRosa instancePath: " + instancePath);
                } else {
                    throw new AssertionError("Unexpected question in nested-repeat test: " + questionName);
                }

                Map<String, Object> answerRequest = new LinkedHashMap<>();
                answerRequest.put("questionPath", question.path("path").asText());
                answerRequest.put("instancePath", question.path("instancePath").asText());
                answerRequest.put("value", value);
                if (!question.path("repeatPath").isNull() && !question.path("repeatPath").asText().isBlank()) {
                    answerRequest.put("repeatPath", question.path("repeatPath").asText());
                }
                if (!question.path("repeatIndex").isNull() && !question.path("repeatIndex").isMissingNode()) {
                    answerRequest.put("repeatIndex", question.path("repeatIndex").asInt());
                }

                mockMvc.perform(post("/api/web/sessions/{sessionId}/answers", sessionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(answerRequest)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.validationStatus").value("valid"));
            }

            MvcResult nextResult = mockMvc.perform(
                            post("/api/web/sessions/{sessionId}/navigate/next", sessionId))
                    .andExpect(status().isOk())
                    .andReturn();
            state = objectMapper.readTree(nextResult.getResponse().getContentAsString());
        }

        assertTrue(safetyCounter < 100, "Nested repeat navigation did not reach end of form");
        assertEquals(2, memberNumber, "Both outer repeat instances must be visited");
        assertEquals(4, noteValues.size(), "Two visits must be materialized for each of two members");
        assertEquals(4, noteInstancePaths.size(), "All nested repeat answer targets must be unique");

        MvcResult finalizeResult = mockMvc.perform(
                        post("/api/web/sessions/{sessionId}/finalize", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.submissionId").exists())
                .andReturn();

        JsonNode finalizeResponse = objectMapper.readTree(finalizeResult.getResponse().getContentAsString());
        UUID submissionId = UUID.fromString(finalizeResponse.path("submissionId").asText());
        Submission submission = submissionRepository.findById(submissionId).orElseThrow();
        assertNotNull(submission.getXmlContent());

        Document document = parseXml(submission.getXmlContent());
        NodeList memberNodes = document.getElementsByTagNameNS("*", "member");
        NodeList noteNodes = document.getElementsByTagNameNS("*", "note");

        assertEquals(2, memberNodes.getLength());
        assertEquals(4, noteNodes.getLength());

        List<String> persistedNotes = new ArrayList<>();
        for (int i = 0; i < noteNodes.getLength(); i++) {
            persistedNotes.add(noteNodes.item(i).getTextContent());
        }
        assertEquals(noteValues, persistedNotes);
    }

    private static int eventOf(JsonNode state) {
        if (state.has("event")) {
            return state.path("event").asInt();
        }
        return state.path("currentEvent").asInt();
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }
}
