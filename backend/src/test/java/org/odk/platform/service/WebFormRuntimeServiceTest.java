package org.odk.platform.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.User;
import org.odk.platform.domain.WebFormAnswer;
import org.odk.platform.domain.WebFormAttachment;
import org.odk.platform.domain.WebFormSession;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.UserRepository;
import org.odk.platform.repository.XFormRepository;
import org.odk.platform.repository.WebFormAnswerRepository;
import org.odk.platform.repository.WebFormAttachmentRepository;
import org.odk.platform.repository.WebFormSessionRepository;
import org.odk.platform.service.storage.StorageService;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WebFormRuntimeServiceTest {

    @Autowired
    private WebFormRuntimeService webFormRuntimeService;

    @Autowired
    private FormVersionRepository formVersionRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private XFormRepository xFormRepository;

    @Autowired
    private WebFormSessionRepository sessionRepository;

    @Autowired
    private WebFormAnswerRepository answerRepository;

    @Autowired
    private WebFormAttachmentRepository attachmentRepository;

    /**
     * This suite verifies WebFormRuntimeService behavior and attachment metadata
     * persistence, not LocalStorageService filesystem permissions. Replacing the
     * storage boundary prevents the test from writing to the production path
     * (/var/lib/odk) while preserving the service-level assertions.
     */
    @MockBean
    private StorageService storageService;

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

        // Create an XForm
        XForm xForm = new XForm();
        xForm.setProject(project);
        xForm.setTitle("Simple Form");
        xForm.setXmlns("http://example.org/simple_form");
        xForm.setXmlContent(SIMPLE_XFORM);
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
    void startSession_createsNewSession() {
        WebFormSession session = webFormRuntimeService.startSession(formVersionId, userId, null);

        assertNotNull(session.getId());
        assertEquals(formVersionId, session.getFormVersion().getId());
        assertEquals(userId, session.getUser().getId());
        assertNull(session.getAppUser());
        assertEquals("draft", session.getStatus());
        assertNotNull(session.getStartedAt());
        assertNotNull(session.getLastActivityAt());
    }

    @Test
    void startSession_withoutUser_createsSession() {
        WebFormSession session = webFormRuntimeService.startSession(formVersionId, null, null);

        assertNotNull(session.getId());
        assertEquals(formVersionId, session.getFormVersion().getId());
        assertNull(session.getUser());
        assertNull(session.getAppUser());
        assertEquals("draft", session.getStatus());
    }

    @Test
    void startSession_resumesExistingDraft() {
        // Create first session
        WebFormSession session1 = webFormRuntimeService.startSession(formVersionId, userId, null);
        UUID sessionId1 = session1.getId();

        // Try to start another session for same user/form - should resume
        WebFormSession session2 = webFormRuntimeService.startSession(formVersionId, userId, null);

        assertEquals(sessionId1, session2.getId());
        assertEquals("draft", session2.getStatus());
    }

    @Test
    void getSession_returnsSession() {
        WebFormSession session = webFormRuntimeService.startSession(formVersionId, userId, null);

        WebFormSession retrieved = webFormRuntimeService.getSession(session.getId());

        assertEquals(session.getId(), retrieved.getId());
        assertEquals(formVersionId, retrieved.getFormVersion().getId());
    }

    @Test
    void getSession_invalidId_throwsException() {
        UUID invalidId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> {
            webFormRuntimeService.getSession(invalidId);
        });
    }

    @Test
    void saveAnswer_savesAnswerAndUpdatesSession() {
        WebFormSession session = webFormRuntimeService.startSession(formVersionId, userId, null);

        // Navigate to first question
        var formDef = webFormRuntimeService.getFormDefinition(session.getId());
        formDef.stepToNextEvent(); // Move to first question

        // Save answer
        WebFormAnswer answer = webFormRuntimeService.saveAnswer(
                session.getId(),
                "name",
                "John",
                null,
                null
        );

        assertNotNull(answer.getId());
        assertEquals(session.getId(), answer.getSession().getId());
        assertEquals("name", answer.getQuestionPath());
        assertEquals("John", answer.getValueText());
        assertEquals("valid", answer.getValidationStatus());
    }

    @Test
    void saveAnswer_validatesRequiredField() {
        WebFormSession session = webFormRuntimeService.startSession(formVersionId, userId, null);

        var formDef = webFormRuntimeService.getFormDefinition(session.getId());
        formDef.stepToNextEvent(); // Move to first question (name - required)

        // Try to save empty answer for required field
        assertThrows(IllegalArgumentException.class, () -> {
            webFormRuntimeService.saveAnswer(session.getId(), "name", "", null, null);
        });
    }

    @Test
    void saveAnswer_validatesConstraint() {
        WebFormSession session = webFormRuntimeService.startSession(formVersionId, userId, null);

        var formDef = webFormRuntimeService.getFormDefinition(session.getId());
        formDef.stepToNextEvent(); // name
        webFormRuntimeService.saveAnswer(session.getId(), "name", "John", null, null);

        formDef.stepToNextEvent(); // age
        // Try to save invalid age (constraint: > 0 and < 120)
        assertThrows(IllegalArgumentException.class, () -> {
            webFormRuntimeService.saveAnswer(session.getId(), "age", "150", null, null);
        });
    }

    @Test
    void finalizeSession_createsSubmission() {
        WebFormSession session = webFormRuntimeService.startSession(formVersionId, userId, null);

        var formDef = webFormRuntimeService.getFormDefinition(session.getId());
        formDef.stepToNextEvent(); // name
        webFormRuntimeService.saveAnswer(session.getId(), "name", "John", null, null);

        formDef.stepToNextEvent(); // age
        webFormRuntimeService.saveAnswer(session.getId(), "age", "30", null, null);

        // Finalize
        var submission = webFormRuntimeService.finalizeSession(session.getId());

        assertNotNull(submission);
        assertEquals(formVersionId, submission.getFormVersion().getId());
        assertNotNull(submission.getInstanceId());
        assertNotNull(submission.getXmlContent());
        assertTrue(submission.getXmlContent().contains("John"));
        assertTrue(submission.getXmlContent().contains("30"));
    }

    @Test
    void finalizeSession_invalidStatus_throwsException() {
        WebFormSession session = webFormRuntimeService.startSession(formVersionId, userId, null);
        session.setStatus("submitted");
        sessionRepository.save(session);

        assertThrows(IllegalStateException.class, () -> {
            webFormRuntimeService.finalizeSession(session.getId());
        });
    }

    @Test
    void updateSessionStatus_updatesStatus() {
        WebFormSession session = webFormRuntimeService.startSession(formVersionId, userId, null);

        WebFormSession updated = webFormRuntimeService.updateSessionStatus(session.getId(), "completed");

        assertEquals("completed", updated.getStatus());
        assertNotNull(updated.getCompletedAt());
    }

    @Test
    void deleteSession_deletesSessionAndAnswers() {
        WebFormSession session = webFormRuntimeService.startSession(formVersionId, userId, null);

        var formDef = webFormRuntimeService.getFormDefinition(session.getId());
        formDef.stepToNextEvent();
        webFormRuntimeService.saveAnswer(session.getId(), "name", "John", null, null);

        UUID sessionId = session.getId();

        webFormRuntimeService.deleteSession(sessionId);

        assertThrows(IllegalArgumentException.class, () -> {
            webFormRuntimeService.getSession(sessionId);
        });

        List<WebFormAnswer> answers = answerRepository.findBySessionId(sessionId);
        assertTrue(answers.isEmpty());
    }

    @Test
    void saveAttachment_savesAttachment() throws Exception {
        WebFormSession session = webFormRuntimeService.startSession(formVersionId, userId, null);

        byte[] content = "test content".getBytes();
        WebFormAttachment attachment = webFormRuntimeService.saveAttachment(
                session.getId(),
                null,
                "name",
                "test.txt",
                "text/plain",
                content.length,
                content
        );

        assertNotNull(attachment.getId());
        assertEquals(session.getId(), attachment.getSession().getId());
        assertEquals("test.txt", attachment.getFileName());
        assertEquals("text/plain", attachment.getContentType());
        assertEquals(content.length, attachment.getFileSize());
        assertNotNull(attachment.getContentHash());
    }

    @Test
    void getFormDefinition_returnsFormDefinition() {
        WebFormSession session = webFormRuntimeService.startSession(formVersionId, userId, null);

        var formDef = webFormRuntimeService.getFormDefinition(session.getId());

        assertNotNull(formDef);
        assertEquals(formVersionId.toString(), formDef.getFormVersionId());
        assertEquals("Simple Form", formDef.getFormTitle());
    }

    @Test
    void getFormDefinitionWithInstance_returnsFormDefinition() {
        WebFormSession session = webFormRuntimeService.startSession(formVersionId, userId, null);

        var formDef = webFormRuntimeService.getFormDefinitionWithInstance(session.getId());

        assertNotNull(formDef);
        assertEquals(formVersionId.toString(), formDef.getFormVersionId());
    }

    @Test
    void reEvaluateForm_updatesRelevance() {
        // Create form with relevance
        String relevanceXform = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>Relevance Form</h:title>
                        <model>
                            <instance>
                                <data id="relevance_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                    <show_extra/>
                                    <extra_field/>
                                    <meta>
                                        <instanceID/>
                                    </meta>
                                </data>
                            </instance>
                            <bind nodeset="/data/show_extra" type="boolean"/>
                            <bind nodeset="/data/extra_field" type="string" relevant="/data/show_extra = true()"/>
                        </model>
                    </h:head>
                    <h:body>
                        <input ref="/data/show_extra">
                            <label>Show Extra Field?</label>
                        </input>
                        <input ref="/data/extra_field">
                            <label>Extra Field</label>
                        </input>
                    </h:body>
                </h:html>
                """;

        FormVersion relevanceFormVersion = new FormVersion();
        relevanceFormVersion.setForm(xFormRepository.findAll().get(0));
        relevanceFormVersion.setXmlContent(relevanceXform);
        relevanceFormVersion.setVersion("1.0.0");
        relevanceFormVersion.setCreatedBy(userRepository.findById(userId).get());
        relevanceFormVersion = formVersionRepository.save(relevanceFormVersion);

        WebFormSession session = webFormRuntimeService.startSession(relevanceFormVersion.getId(), userId, null);

        var formDef = webFormRuntimeService.getFormDefinition(session.getId());
        formDef.stepToNextEvent(); // show_extra
        webFormRuntimeService.saveAnswer(session.getId(), "show_extra", "true", null, null);

        // The extra_field should now be relevant
        // This test verifies the re-evaluation logic runs
        List<WebFormAnswer> answers = answerRepository.findBySessionId(session.getId());
        assertEquals(1, answers.size());
    }

    @Test
    void getAnswersForSession_returnsAnswers() {
        WebFormSession session = webFormRuntimeService.startSession(formVersionId, userId, null);

        var formDef = webFormRuntimeService.getFormDefinition(session.getId());
        formDef.stepToNextEvent();
        webFormRuntimeService.saveAnswer(session.getId(), "name", "John", null, null);

        formDef.stepToNextEvent();
        webFormRuntimeService.saveAnswer(session.getId(), "age", "30", null, null);

        List<WebFormAnswer> answers = answerRepository.findBySessionId(session.getId());

        assertEquals(2, answers.size());
    }

    @Test
    void getAttachmentsForSession_returnsAttachments() throws Exception {
        WebFormSession session = webFormRuntimeService.startSession(formVersionId, userId, null);

        byte[] content = "test".getBytes();
        webFormRuntimeService.saveAttachment(session.getId(), null, "name", "test.txt", "text/plain", content.length, content);

        List<WebFormAttachment> attachments = attachmentRepository.findBySessionId(session.getId());

        assertEquals(1, attachments.size());
    }
}