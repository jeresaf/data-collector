package org.odk.platform.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.User;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.UserRepository;
import org.odk.platform.repository.XFormRepository;
import org.javarosa.form.api.FormEntryController;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WebFormControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FormVersionRepository formVersionRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    private static final String SIMPLE_XFORM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Simple Form</h:title>
                    <model>
                        <instance>
                            <data id="simple_form" version="1.0.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
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

    private UUID formVersionId;
    private UUID userId;
    private UUID projectId;

    @Autowired
    private XFormRepository xFormRepository;

    @BeforeEach
    void setUp() {
        // Create a test user
        User user = new User();
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setPasswordHash("hashed");
        user.setIsActive(true);
        user = userRepository.save(user);
        userId = user.getId();

        // Create a test project
        Project project = new Project();
        project.setName("Test Project");
        project.setOwner(user);
        project.setCreatedBy(user);
        project = projectRepository.save(project);
        projectId = project.getId();

        // Create an XForm first
        XForm xForm = new XForm();
        xForm.setTitle("Simple Form");
        xForm.setXmlns("http://example.org/simple_form");
        xForm.setVersion("1.0.0");
        xForm.setXmlContent(SIMPLE_XFORM);
        xForm.setProject(project);
        xForm.setCreatedBy(user);
        xForm = xFormRepository.save(xForm);

        // Create a form version
        FormVersion formVersion = new FormVersion();
        formVersion.setForm(xForm);
        formVersion.setXmlContent(SIMPLE_XFORM);
        formVersion.setVersion("1.0.0");
        formVersion.setCreatedBy(user);
        formVersion = formVersionRepository.save(formVersion);
        formVersionId = formVersion.getId();
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    void startSession_createsNewSession() throws Exception {
        var request = new WebFormController.StartSessionRequest();
        request.setUserId(userId);
        request.setAppUserId(null);

        MvcResult result = mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").exists())
                .andExpect(jsonPath("$.formVersionId").value(formVersionId.toString()))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.currentEvent").exists())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        var responseObj = objectMapper.readValue(response, WebFormController.StartSessionResponse.class);
        assertNotNull(responseObj.getSessionId());
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    void startSession_withoutUserId_createsSession() throws Exception {
        var request = new WebFormController.StartSessionRequest();
        request.setUserId(null);
        request.setAppUserId(null);

        mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").exists())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    void startSession_invalidFormVersion_returns404() throws Exception {
        UUID invalidId = UUID.randomUUID();
        var request = new WebFormController.StartSessionRequest();
        request.setUserId(userId);

        mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", invalidId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    void getSession_returnsSessionState() throws Exception {
        // First create a session
        var startRequest = new WebFormController.StartSessionRequest();
        startRequest.setUserId(userId);

        MvcResult startResult = mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startRequest)))
                .andExpect(status().isOk())
                .andReturn();

        var startResponse = objectMapper.readValue(
                startResult.getResponse().getContentAsString(),
                WebFormController.StartSessionResponse.class
        );

        // Now get the session
        mockMvc.perform(get("/api/web/sessions/{sessionId}", startResponse.getSessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(startResponse.getSessionId().toString()))
                .andExpect(jsonPath("$.formVersionId").value(formVersionId.toString()))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.startedAt").exists());
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    void getCurrentScreen_returnsCurrentState() throws Exception {
        // First create a session
        var startRequest = new WebFormController.StartSessionRequest();
        startRequest.setUserId(userId);

        MvcResult startResult = mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startRequest)))
                .andExpect(status().isOk())
                .andReturn();

        var startResponse = objectMapper.readValue(
                startResult.getResponse().getContentAsString(),
                WebFormController.StartSessionResponse.class
        );

        // Get current screen
        mockMvc.perform(get("/api/web/sessions/{sessionId}/current", startResponse.getSessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event").exists())
                .andExpect(jsonPath("$.isAtBeginning").exists())
                .andExpect(jsonPath("$.isAtEnd").exists());
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    void saveAnswer_savesAnswerAndReturnsNext() throws Exception {
        // First create a session
        var startRequest = new WebFormController.StartSessionRequest();
        startRequest.setUserId(userId);

        MvcResult startResult = mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startRequest)))
                .andExpect(status().isOk())
                .andReturn();

        var startResponse = objectMapper.readValue(
                startResult.getResponse().getContentAsString(),
                WebFormController.StartSessionResponse.class
        );

        // Save answer for first question (name)
        var answerRequest = new WebFormController.SaveAnswerRequest();
        answerRequest.setQuestionPath("name");
        answerRequest.setValue("John");
        answerRequest.setRepeatPath(null);
        answerRequest.setRepeatIndex(null);

        mockMvc.perform(post("/api/web/sessions/{sessionId}/answers", startResponse.getSessionId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(answerRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerId").exists())
                .andExpect(jsonPath("$.questionPath").value("name"))
                .andExpect(jsonPath("$.value").value("John"))
                .andExpect(jsonPath("$.currentEvent").exists());
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    void navigateNext_movesToNextQuestion() throws Exception {
        // First create a session
        var startRequest = new WebFormController.StartSessionRequest();
        startRequest.setUserId(userId);

        MvcResult startResult = mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startRequest)))
                .andExpect(status().isOk())
                .andReturn();

        var startResponse = objectMapper.readValue(
                startResult.getResponse().getContentAsString(),
                WebFormController.StartSessionResponse.class
        );

        // Navigate next
        mockMvc.perform(post("/api/web/sessions/{sessionId}/navigate/next", startResponse.getSessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event").exists())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    void navigatePrevious_movesToPreviousQuestion() throws Exception {
        // First create a session
        var startRequest = new WebFormController.StartSessionRequest();
        startRequest.setUserId(userId);

        MvcResult startResult = mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startRequest)))
                .andExpect(status().isOk())
                .andReturn();

        var startResponse = objectMapper.readValue(
                startResult.getResponse().getContentAsString(),
                WebFormController.StartSessionResponse.class
        );

        // Move to second question first
        mockMvc.perform(post("/api/web/sessions/{sessionId}/navigate/next", startResponse.getSessionId()))
                .andExpect(status().isOk());

        // Then navigate previous
        mockMvc.perform(post("/api/web/sessions/{sessionId}/navigate/previous", startResponse.getSessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event").exists())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    void finalizeSession_completesSession() throws Exception {
        // First create a session
        var startRequest = new WebFormController.StartSessionRequest();
        startRequest.setUserId(userId);

        MvcResult startResult = mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startRequest)))
                .andExpect(status().isOk())
                .andReturn();

        var startResponse = objectMapper.readValue(
                startResult.getResponse().getContentAsString(),
                WebFormController.StartSessionResponse.class
        );

        // Answer first question
        var answerRequest1 = new WebFormController.SaveAnswerRequest();
        answerRequest1.setQuestionPath("name");
        answerRequest1.setValue("John");

        mockMvc.perform(post("/api/web/sessions/{sessionId}/answers", startResponse.getSessionId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(answerRequest1)))
                .andExpect(status().isOk());

        // Navigate to second question
        mockMvc.perform(post("/api/web/sessions/{sessionId}/navigate/next", startResponse.getSessionId()))
                .andExpect(status().isOk());

        // Answer second question
        var answerRequest2 = new WebFormController.SaveAnswerRequest();
        answerRequest2.setQuestionPath("age");
        answerRequest2.setValue("30");

        mockMvc.perform(post("/api/web/sessions/{sessionId}/answers", startResponse.getSessionId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(answerRequest2)))
                .andExpect(status().isOk());

        // Finalize
        mockMvc.perform(post("/api/web/sessions/{sessionId}/finalize", startResponse.getSessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.submissionId").exists())
                .andExpect(jsonPath("$.instanceId").exists())
                .andExpect(jsonPath("$.message").value("Form submitted successfully"));
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    void saveDraft_updatesSessionStatus() throws Exception {
        // First create a session
        var startRequest = new WebFormController.StartSessionRequest();
        startRequest.setUserId(userId);

        MvcResult startResult = mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startRequest)))
                .andExpect(status().isOk())
                .andReturn();

        var startResponse = objectMapper.readValue(
                startResult.getResponse().getContentAsString(),
                WebFormController.StartSessionResponse.class
        );

        // Save draft
        mockMvc.perform(post("/api/web/sessions/{sessionId}/draft", startResponse.getSessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("draft"));
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    void validateForm_validatesForm() throws Exception {
        // First create a session
        var startRequest = new WebFormController.StartSessionRequest();
        startRequest.setUserId(userId);

        MvcResult startResult = mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startRequest)))
                .andExpect(status().isOk())
                .andReturn();

        var startResponse = objectMapper.readValue(
                startResult.getResponse().getContentAsString(),
                WebFormController.StartSessionResponse.class
        );

        // Validate without answers (should fail due to required field)
        mockMvc.perform(post("/api/web/sessions/{sessionId}/validate", startResponse.getSessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.failedPromptIndex").exists())
                .andExpect(jsonPath("$.outcomeCode").exists());
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    void getFormDefinition_returnsFormMetadata() throws Exception {
        mockMvc.perform(get("/api/web/forms/{formVersionId}/definition", formVersionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formVersionId").value(formVersionId.toString()))
                .andExpect(jsonPath("$.title").value("Simple Form"))
                .andExpect(jsonPath("$.xmlFormId").value("simple_form"))
                .andExpect(jsonPath("$.availableLocales").exists());
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    void deleteSession_deletesSession() throws Exception {
        // First create a session
        var startRequest = new WebFormController.StartSessionRequest();
        startRequest.setUserId(userId);

        MvcResult startResult = mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", formVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startRequest)))
                .andExpect(status().isOk())
                .andReturn();

        var startResponse = objectMapper.readValue(
                startResult.getResponse().getContentAsString(),
                WebFormController.StartSessionResponse.class
        );

        // Delete session
        mockMvc.perform(delete("/api/web/sessions/{sessionId}", startResponse.getSessionId()))
                .andExpect(status().isOk());

        // Verify session is deleted
        mockMvc.perform(get("/api/web/sessions/{sessionId}", startResponse.getSessionId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    void repeatOperations_workCorrectly() throws Exception {
        // Create a form with repeat
        String repeatXform = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>Repeat Form</h:title>
                        <model>
                            <instance>
                                <data id="repeat_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                    <household>
                                        <name/>
                                        <members>
                                            <member jr:template="">
                                                <name/>
                                                <age/>
                                            </member>
                                        </members>
                                    </household>
                                    <meta>
                                        <instanceID/>
                                    </meta>
                                </data>
                            </instance>
                            <bind nodeset="/data/household/name" type="string" required="true()"/>
                            <bind nodeset="/data/household/members/member" type="repeat"/>
                            <bind nodeset="/data/household/members/member/name" type="string"/>
                            <bind nodeset="/data/household/members/member/age" type="int"/>
                        </model>
                    </h:head>
                    <h:body>
                        <group ref="/data/household">
                            <label>Household</label>
                            <input ref="/data/household/name">
                                <label>Household Name</label>
                            </input>
                            <repeat nodeset="/data/household/members/member">
                                <label>Household Member</label>
                                <input ref="/data/household/members/member/name">
                                    <label>Member Name</label>
                                </input>
                                <input ref="/data/household/members/member/age">
                                    <label>Member Age</label>
                                </input>
                            </repeat>
                        </group>
                    </h:body>
                </h:html>
                """;

        // Create an XForm for the repeat form
        Project project = projectRepository.findById(projectId).orElseThrow();
        User user = userRepository.findById(userId).get();
        
        XForm repeatXForm = new XForm();
        repeatXForm.setTitle("Repeat Form");
        repeatXForm.setXmlns("http://example.org/repeat_form");
        repeatXForm.setVersion("1.0.0");
        repeatXForm.setXmlContent(repeatXform);
        repeatXForm.setProject(project);
        repeatXForm.setCreatedBy(user);
        repeatXForm = xFormRepository.save(repeatXForm);

        FormVersion repeatFormVersion = new FormVersion();
        repeatFormVersion.setForm(repeatXForm);
        repeatFormVersion.setXmlContent(repeatXform);
        repeatFormVersion.setVersion("1.0.0");
        repeatFormVersion.setCreatedBy(user);
        repeatFormVersion = formVersionRepository.save(repeatFormVersion);

        // Start session with repeat form
        var startRequest = new WebFormController.StartSessionRequest();
        startRequest.setUserId(userId);

        MvcResult startResult = mockMvc.perform(post("/api/web/forms/{formVersionId}/sessions", repeatFormVersion.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startRequest)))
                .andExpect(status().isOk())
                .andReturn();

        var startResponse = objectMapper.readValue(
                startResult.getResponse().getContentAsString(),
                WebFormController.StartSessionResponse.class
        );

        // Navigate to household name
        mockMvc.perform(post("/api/web/sessions/{sessionId}/navigate/next", startResponse.getSessionId()))
                .andExpect(status().isOk());

        // Answer household name
        var answerRequest = new WebFormController.SaveAnswerRequest();
        answerRequest.setQuestionPath("household/name");
        answerRequest.setValue("Smith Family");

        mockMvc.perform(post("/api/web/sessions/{sessionId}/answers", startResponse.getSessionId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(answerRequest)))
                .andExpect(status().isOk());

        // Navigate to repeat juncture
        mockMvc.perform(post("/api/web/sessions/{sessionId}/navigate/next", startResponse.getSessionId()))
                .andExpect(status().isOk());

        // Create new repeat
        mockMvc.perform(post("/api/web/sessions/{sessionId}/repeats/new", startResponse.getSessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event").exists());

        // Answer member name
        var memberAnswer = new WebFormController.SaveAnswerRequest();
        memberAnswer.setQuestionPath("household/members/member/name");
        memberAnswer.setValue("John");
        memberAnswer.setRepeatPath("household/members/member");
        memberAnswer.setRepeatIndex(0);

        mockMvc.perform(post("/api/web/sessions/{sessionId}/answers", startResponse.getSessionId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberAnswer)))
                .andExpect(status().isOk());

        // Navigate to member age
        mockMvc.perform(post("/api/web/sessions/{sessionId}/navigate/next", startResponse.getSessionId()))
                .andExpect(status().isOk());

        // Answer member age
        var ageAnswer = new WebFormController.SaveAnswerRequest();
        ageAnswer.setQuestionPath("household/members/member/age");
        ageAnswer.setValue("10");
        ageAnswer.setRepeatPath("household/members/member");
        ageAnswer.setRepeatIndex(0);

        mockMvc.perform(post("/api/web/sessions/{sessionId}/answers", startResponse.getSessionId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ageAnswer)))
                .andExpect(status().isOk());
    }
    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    void repeatCountControlledRepeat_isJavaRosaManaged() throws Exception {
        String repeatCountXform = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                        xmlns="http://www.w3.org/2002/xforms"
                        xmlns:jr="http://openrosa.org/javarosa">
                    <h:head>
                        <h:title>Repeat Count Form</h:title>
                        <model>
                            <instance>
                                <data id="repeat_count_form" version="1.0">
                                    <repeat_count>2</repeat_count>
                                    <members>
                                        <member jr:template="">
                                            <name/>
                                        </member>
                                    </members>
                                    <meta>
                                        <instanceID/>
                                    </meta>
                                </data>
                            </instance>
                            <bind nodeset="/data/repeat_count" type="int"/>
                            <bind nodeset="/data/members/member/name" type="string"/>
                        </model>
                    </h:head>
                    <h:body>
                        <group ref="/data/members">
                            <label>Members</label>
                            <repeat nodeset="/data/members/member" jr:count="/data/repeat_count">
                                <input ref="/data/members/member/name">
                                    <label>Member Name</label>
                                </input>
                            </repeat>
                        </group>
                    </h:body>
                </h:html>
                """;

        Project project = projectRepository.findById(projectId).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();

        XForm repeatCountForm = new XForm();
        repeatCountForm.setTitle("Repeat Count Form");
        repeatCountForm.setXmlns("http://example.org/repeat_count_form");
        repeatCountForm.setVersion("1.0.0");
        repeatCountForm.setXmlContent(repeatCountXform);
        repeatCountForm.setProject(project);
        repeatCountForm.setCreatedBy(user);
        repeatCountForm = xFormRepository.save(repeatCountForm);

        FormVersion repeatCountVersion = new FormVersion();
        repeatCountVersion.setForm(repeatCountForm);
        repeatCountVersion.setXmlContent(repeatCountXform);
        repeatCountVersion.setVersion("1.0.0");
        repeatCountVersion.setCreatedBy(user);
        repeatCountVersion = formVersionRepository.save(repeatCountVersion);

        var startRequest = new WebFormController.StartSessionRequest();
        startRequest.setUserId(userId);

        MvcResult startResult = mockMvc.perform(
                        post("/api/web/forms/{formVersionId}/sessions", repeatCountVersion.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(startRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentEvent").value(FormEntryController.EVENT_REPEAT))
                .andExpect(jsonPath("$.group.repeatCount").value(1))
                .andExpect(jsonPath("$.group.currentRepeatIndex").value(0))
                .andExpect(jsonPath("$.group.repeatTargetCount").value(2))
                .andExpect(jsonPath("$.group.repeatCountControlled").value(true))
                .andExpect(jsonPath("$.group.canAddRepeat").value(false))
                .andExpect(jsonPath("$.group.canDeleteRepeat").value(false))
                .andReturn();

        WebFormController.StartSessionResponse startResponse = objectMapper.readValue(
                startResult.getResponse().getContentAsString(),
                WebFormController.StartSessionResponse.class);
        UUID sessionId = startResponse.getSessionId();

        // repeat_count is authoritative: users cannot change its cardinality.
        mockMvc.perform(post("/api/web/sessions/{sessionId}/repeats/new", sessionId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("repeat_count")));

        mockMvc.perform(post("/api/web/sessions/{sessionId}/repeats/delete", sessionId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("repeat_count")));

        // Enter the first automatically-created repeat instance.
        mockMvc.perform(post("/api/web/sessions/{sessionId}/navigate/next", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event").value(FormEntryController.EVENT_QUESTION))
                .andExpect(jsonPath("$.question.name").value("name"))
                .andExpect(jsonPath("$.question.path").value("members/member/name"))
                .andExpect(jsonPath("$.question.repeatPath").value("members/member"))
                .andExpect(jsonPath("$.question.repeatIndex").value(0));

        var firstMember = new WebFormController.SaveAnswerRequest();
        firstMember.setQuestionPath("members/member/name");
        firstMember.setValue("Member 1");
        firstMember.setRepeatPath("members/member");
        firstMember.setRepeatIndex(0);

        mockMvc.perform(post("/api/web/sessions/{sessionId}/answers", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstMember)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validationStatus").value("valid"));

        // JavaRosa materializes repeat 2 when navigation reaches multiplicity 1.
        mockMvc.perform(post("/api/web/sessions/{sessionId}/navigate/next", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event").value(FormEntryController.EVENT_REPEAT))
                .andExpect(jsonPath("$.group.repeatCount").value(2))
                .andExpect(jsonPath("$.group.currentRepeatIndex").value(1))
                .andExpect(jsonPath("$.group.repeatTargetCount").value(2))
                .andExpect(jsonPath("$.group.repeatCountControlled").value(true))
                .andExpect(jsonPath("$.group.canAddRepeat").value(false))
                .andExpect(jsonPath("$.group.canDeleteRepeat").value(false));

        mockMvc.perform(post("/api/web/sessions/{sessionId}/navigate/next", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event").value(FormEntryController.EVENT_QUESTION))
                .andExpect(jsonPath("$.question.path").value("members/member/name"))
                .andExpect(jsonPath("$.question.repeatPath").value("members/member"))
                .andExpect(jsonPath("$.question.repeatIndex").value(1));

        var secondMember = new WebFormController.SaveAnswerRequest();
        secondMember.setQuestionPath("members/member/name");
        secondMember.setValue("Member 2");
        secondMember.setRepeatPath("members/member");
        secondMember.setRepeatIndex(1);

        mockMvc.perform(post("/api/web/sessions/{sessionId}/answers", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondMember)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validationStatus").value("valid"));
    }

}