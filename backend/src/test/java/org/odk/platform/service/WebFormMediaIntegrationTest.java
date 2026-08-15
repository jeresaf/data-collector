package org.odk.platform.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.SubmissionAttachment;
import org.odk.platform.domain.User;
import org.odk.platform.domain.WebFormAttachment;
import org.odk.platform.domain.WebFormSession;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.SubmissionAttachmentRepository;
import org.odk.platform.repository.UserRepository;
import org.odk.platform.repository.WebFormAttachmentRepository;
import org.odk.platform.repository.XFormRepository;
import org.odk.platform.service.storage.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WebFormMediaIntegrationTest {

    private static final String MEDIA_XFORM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms"
                    xmlns:jr="http://openrosa.org/javarosa">
              <h:head>
                <h:title>Media Form</h:title>
                <model>
                  <instance>
                    <data id="media_form" version="1">
                      <photo/>
                      <meta><instanceID/></meta>
                    </data>
                  </instance>
                  <bind nodeset="/data/photo" type="binary"/>
                </model>
              </h:head>
              <h:body>
                <upload ref="/data/photo" mediatype="image/*">
                  <label>Photo</label>
                </upload>
              </h:body>
            </h:html>
            """;

    @Autowired
    private WebFormRuntimeService runtimeService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private XFormRepository xFormRepository;

    @Autowired
    private FormVersionRepository formVersionRepository;

    @Autowired
    private WebFormAttachmentRepository webFormAttachmentRepository;

    @Autowired
    private SubmissionAttachmentRepository submissionAttachmentRepository;

    @MockBean
    private StorageService storageService;

    private final Map<String, byte[]> storedObjects = new ConcurrentHashMap<>();

    private UUID formVersionId;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        storedObjects.clear();

        doAnswer(invocation -> {
            InputStream input = invocation.getArgument(0);
            String path = invocation.getArgument(1);
            storedObjects.put(path, input.readAllBytes());
            return null;
        }).when(storageService).store(any(InputStream.class), anyString(), anyString());

        when(storageService.read(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            byte[] content = storedObjects.get(path);
            if (content == null) {
                throw new IOException("Missing test storage object: " + path);
            }
            return content;
        });

        doAnswer(invocation -> {
            String path = invocation.getArgument(0);
            storedObjects.remove(path);
            return null;
        }).when(storageService).delete(anyString());

        User user = new User();
        user.setUsername("media-user-" + UUID.randomUUID());
        user.setEmail("media-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("hashed");
        user.setIsActive(true);
        user = userRepository.save(user);
        userId = user.getId();

        Project project = new Project();
        project.setName("Media Project");
        project.setOwner(user);
        project.setCreatedBy(user);
        project = projectRepository.save(project);

        XForm form = new XForm();
        form.setProject(project);
        form.setTitle("Media Form");
        form.setXmlns("http://example.org/media_form");
        form.setXmlContent(MEDIA_XFORM);
        form.setCreatedBy(user);
        form = xFormRepository.save(form);

        FormVersion version = new FormVersion();
        version.setForm(form);
        version.setVersion("1");
        version.setXmlContent(MEDIA_XFORM);
        version.setCreatedBy(user);
        version = formVersionRepository.save(version);
        formVersionId = version.getId();
    }

    @Test
    void uploadQuestion_exposesJavaRosaMediaType() {
        WebFormSession session = runtimeService.startSession(formVersionId, userId, null);
        FormDefinition definition = runtimeService.getFormDefinition(session.getId());

        int event = definition.stepToNextEvent();
        while (event != org.javarosa.form.api.FormEntryController.EVENT_QUESTION
                && event != org.javarosa.form.api.FormEntryController.EVENT_END_OF_FORM) {
            event = definition.stepToNextEvent();
        }

        assertEquals(org.javarosa.form.api.FormEntryController.EVENT_QUESTION, event);
        assertEquals("image/*", definition.getCurrentMediaType());
    }

    @Test
    void sameOriginalFilename_usesDistinctTemporaryStorageObjects() throws Exception {
        WebFormSession session = runtimeService.startSession(formVersionId, userId, null);

        WebFormAttachment first = runtimeService.saveAttachment(
                session.getId(), null, "photo", "photo.jpg", "image/jpeg", 3,
                new byte[]{1, 2, 3});
        WebFormAttachment second = runtimeService.saveAttachment(
                session.getId(), null, "photo", "photo.jpg", "image/jpeg", 3,
                new byte[]{4, 5, 6});

        assertEquals("photo.jpg", first.getFileName());
        assertEquals("photo.jpg", second.getFileName());
        assertNotEquals(first.getStoragePath(), second.getStoragePath());
        assertArrayEquals(new byte[]{1, 2, 3}, storedObjects.get(first.getStoragePath()));
        assertArrayEquals(new byte[]{4, 5, 6}, storedObjects.get(second.getStoragePath()));
    }

    @Test
    void unsafeFilename_isRejectedBeforeStorage() {
        WebFormSession session = runtimeService.startSession(formVersionId, userId, null);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeService.saveAttachment(
                        session.getId(), null, "photo", "../photo.jpg", "image/jpeg", 3,
                        new byte[]{1, 2, 3}));

        assertEquals("Invalid attachment filename", error.getMessage());
        assertTrue(storedObjects.isEmpty());
    }

    @Test
    void mismatchedFileSize_isRejectedBeforeStorage() {
        WebFormSession session = runtimeService.startSession(formVersionId, userId, null);

        assertThrows(
                IllegalArgumentException.class,
                () -> runtimeService.saveAttachment(
                        session.getId(), null, "photo", "photo.jpg", "image/jpeg", 99,
                        new byte[]{1, 2, 3}));

        assertTrue(storedObjects.isEmpty());
    }

    @Test
    void finalize_movesAttachmentToSubmission_andSerializesOriginalFilename() throws Exception {
        WebFormSession session = runtimeService.startSession(formVersionId, userId, null);
        byte[] bytes = new byte[]{10, 20, 30, 40};

        WebFormAttachment webAttachment = runtimeService.saveAttachment(
                session.getId(), null, "photo", "field-photo.jpg", "image/jpeg",
                bytes.length, bytes);
        String temporaryPath = webAttachment.getStoragePath();

        runtimeService.saveAnswer(
                session.getId(), "photo", "field-photo.jpg", null, null);

        Submission submission = runtimeService.finalizeSession(session.getId());

        assertTrue(submission.getXmlContent().contains("<photo>field-photo.jpg</photo>"));

        List<SubmissionAttachment> submissionAttachments =
                submissionAttachmentRepository.findBySubmissionId(submission.getId());
        assertEquals(1, submissionAttachments.size());

        SubmissionAttachment persisted = submissionAttachments.get(0);
        assertEquals("field-photo.jpg", persisted.getFileName());
        assertEquals("image/jpeg", persisted.getContentType());
        assertEquals(bytes.length, persisted.getFileSize());
        assertEquals("photo", persisted.getMetadata().get("questionPath"));
        assertEquals(Boolean.TRUE, persisted.getMetadata().get("referencedInAnswers"));
        assertTrue(persisted.getStoragePath().startsWith("submissions/" + submission.getId() + "/"));
        assertArrayEquals(bytes, storedObjects.get(persisted.getStoragePath()));

        assertFalse(storedObjects.containsKey(temporaryPath));
        assertTrue(webFormAttachmentRepository.findBySessionId(session.getId()).isEmpty());
    }
}
