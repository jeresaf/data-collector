package org.odk.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Submission;
import org.odk.platform.domain.SubmissionAttachment;
import org.odk.platform.domain.User;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.WebFormAnswer;
import org.odk.platform.domain.WebFormAttachment;
import org.odk.platform.domain.WebFormSession;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.repository.WebFormAnswerRepository;
import org.odk.platform.repository.WebFormAttachmentRepository;
import org.odk.platform.repository.WebFormSessionRepository;
import org.odk.platform.repository.XFormRepository;
import org.odk.platform.repository.UserRepository;
import org.odk.platform.repository.AppUserRepository;
import org.odk.platform.repository.SubmissionRepository;
import org.odk.platform.repository.SubmissionAttachmentRepository;
import org.odk.platform.service.FormDefinition;
import org.odk.platform.service.storage.StorageService;
import org.javarosa.core.model.Constants;
import org.javarosa.core.model.QuestionDef;
import org.javarosa.core.model.FormIndex;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.form.api.FormEntryController;
import org.javarosa.form.api.FormEntryPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@Transactional
public class WebFormRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(WebFormRuntimeService.class);

    private final WebFormSessionRepository sessionRepository;
    private final WebFormAnswerRepository answerRepository;
    private final WebFormAttachmentRepository attachmentRepository;
    private final FormVersionRepository formVersionRepository;
    private final XFormRepository xFormRepository;
    private final UserRepository userRepository;
    private final AppUserRepository appUserRepository;
    private final JavaRosaFormLoader javaRosaFormLoader;
    private final SubmissionProcessingService submissionProcessingService;
    private final SubmissionRepository submissionRepository;
    private final SubmissionAttachmentRepository submissionAttachmentRepository;
    private final SubmissionPersistenceService submissionPersistenceService;
    private final FormExternalDatasetService formExternalDatasetService;
    private final EntityListDatasetService entityListDatasetService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public WebFormRuntimeService(
            WebFormSessionRepository sessionRepository,
            WebFormAnswerRepository answerRepository,
            WebFormAttachmentRepository attachmentRepository,
            FormVersionRepository formVersionRepository,
            XFormRepository xFormRepository,
            UserRepository userRepository,
            AppUserRepository appUserRepository,
            JavaRosaFormLoader javaRosaFormLoader,
            SubmissionProcessingService submissionProcessingService,
            SubmissionRepository submissionRepository,
            SubmissionAttachmentRepository submissionAttachmentRepository,
            SubmissionPersistenceService submissionPersistenceService,
            FormExternalDatasetService formExternalDatasetService,
            EntityListDatasetService entityListDatasetService,
            StorageService storageService,
            ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.answerRepository = answerRepository;
        this.attachmentRepository = attachmentRepository;
        this.formVersionRepository = formVersionRepository;
        this.xFormRepository = xFormRepository;
        this.userRepository = userRepository;
        this.appUserRepository = appUserRepository;
        this.javaRosaFormLoader = javaRosaFormLoader;
        this.submissionProcessingService = submissionProcessingService;
        this.submissionRepository = submissionRepository;
        this.submissionAttachmentRepository = submissionAttachmentRepository;
        this.submissionPersistenceService = submissionPersistenceService;
        this.formExternalDatasetService = formExternalDatasetService;
        this.entityListDatasetService = entityListDatasetService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    /**
     * Start a new web form session or resume an existing draft.
     */
    public WebFormSession startSession(UUID formVersionId, UUID userId, UUID appUserId) {
        FormVersion formVersion = formVersionRepository.findById(formVersionId)
                .orElseThrow(() -> new IllegalArgumentException("Form version not found: " + formVersionId));

        // Check for existing draft session
        Optional<WebFormSession> existingDraft = Optional.empty();
        if (userId != null) {
            existingDraft = sessionRepository.findActiveDraftByFormVersionAndUser(formVersionId, userId);
        } else if (appUserId != null) {
            existingDraft = sessionRepository.findActiveDraftByFormVersionAndAppUser(formVersionId, appUserId);
        }

        if (existingDraft.isPresent()) {
            WebFormSession session = existingDraft.get();
            session.setLastActivityAt(OffsetDateTime.now());
            return sessionRepository.save(session);
        }

        // Create new session
        User user = userId != null ? userRepository.findById(userId).orElse(null) : null;
        AppUser appUser = appUserId != null ? appUserRepository.findById(appUserId).orElse(null) : null;

        WebFormSession session = WebFormSession.builder()
                .formVersion(formVersion)
                .user(user)
                .appUser(appUser)
                .status("draft")
                .currentScreen("start")
                .sessionData("{}")
                .startedAt(OffsetDateTime.now())
                .lastActivityAt(OffsetDateTime.now())
                .build();

        return sessionRepository.save(session);
    }

    /**
     * Get session with current state for rendering.
     */
    @Transactional(readOnly = true)
    public WebFormSession getSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
    }

    /** Load a fresh JavaRosa definition for a form version, including active external datasets. */
    @Transactional(readOnly = true)
    public FormDefinition getFormDefinitionForVersion(UUID formVersionId) {
        FormVersion formVersion = formVersionRepository.findById(formVersionId)
                .orElseThrow(() -> new IllegalArgumentException("Form version not found: " + formVersionId));
        return createFormDefinition(formVersion);
    }

    private FormDefinition createFormDefinition(FormVersion formVersion) {
        RuntimeDatasets runtimeDatasets = loadRuntimeDatasets(formVersion);
        return FormDefinition.createNew(
                formVersion.getXmlContent(),
                formVersion.getId().toString(),
                runtimeDatasets.datasets(),
                runtimeDatasets.warnings()
        );
    }

    private FormDefinition createFormDefinition(FormVersion formVersion, String instanceXml) {
        RuntimeDatasets runtimeDatasets = loadRuntimeDatasets(formVersion);
        return FormDefinition.createFromInstance(
                formVersion.getXmlContent(),
                instanceXml,
                formVersion.getId().toString(),
                runtimeDatasets.datasets(),
                runtimeDatasets.warnings()
        );
    }

    /**
     * Merge shared project Entity Lists with form-version attachments.
     * Explicit form-version attachments win on name collisions because they are
     * the most specific data source configured for a particular form version.
     */
    private RuntimeDatasets loadRuntimeDatasets(FormVersion formVersion) {
        EntityListDatasetService.DatasetBundle entityBundle =
                entityListDatasetService.loadReferencedEntityDatasets(formVersion);
        Map<String, byte[]> merged = new LinkedHashMap<>(entityBundle.datasets());
        merged.putAll(formExternalDatasetService.loadActiveDatasetContents(formVersion.getId()));
        return new RuntimeDatasets(Map.copyOf(merged), entityBundle.warnings());
    }

    private record RuntimeDatasets(Map<String, byte[]> datasets, List<String> warnings) {
    }

    /**
     * Get the form definition for a session.
     */
    @Transactional(readOnly = true)
    public FormDefinition getFormDefinition(UUID sessionId) {
        WebFormSession session = getSession(sessionId);
        FormVersion formVersion = session.getFormVersion();
        
        // Load form into JavaRosa for runtime evaluation
        // Use the FormDefinition wrapper which handles parsing and initialization
        return createFormDefinition(formVersion);
    }

    /**
     * Get the form definition for a session with existing instance XML (for resume).
     */
    @Transactional(readOnly = true)
    public FormDefinition getFormDefinitionWithInstance(UUID sessionId, String instanceXml) {
        WebFormSession session = getSession(sessionId);
        FormVersion formVersion = session.getFormVersion();
        
        return createFormDefinition(formVersion, instanceXml);
    }

    /**
     * Get the form definition for a session with existing instance XML from session data.
     */
    @Transactional(readOnly = true)
    public FormDefinition getFormDefinitionWithInstance(UUID sessionId) {
        WebFormSession session = getSession(sessionId);
        FormVersion formVersion = session.getFormVersion();

        String instanceXml = extractInstanceXml(session.getSessionData());
        if (instanceXml != null && !instanceXml.isBlank()) {
            return createFormDefinition(formVersion, instanceXml);
        }

        return createFormDefinition(formVersion);
    }

    @Transactional(readOnly = true)
    public FormDefinition getFormDefinitionWithSessionState(UUID sessionId) {
        WebFormSession session = getSession(sessionId);
        FormDefinition formDef = getFormDefinitionWithInstance(sessionId);
        restoreFormPosition(session, formDef);
        return formDef;
    }

    public WebFormSession persistFormState(UUID sessionId, FormDefinition formDef) {
        WebFormSession session = getSession(sessionId);

        try {
            Map<String, String> state = new HashMap<>();
            state.put("instanceXml", formDef.serializeDraft());
            session.setSessionData(objectMapper.writeValueAsString(state));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize web form session state", e);
        }

        int event = formDef.getCurrentEvent();
        session.setCurrentScreen(Integer.toString(event));

        FormIndex index = formDef.getModel().getFormIndex();
        String reference = null;
        if (index != null && index.isInForm() && index.getReference() != null) {
            reference = index.getReference().toString();
        }
        session.setCurrentQuestionPath(reference);

        session.setLastActivityAt(OffsetDateTime.now());
        return sessionRepository.save(session);
    }

    private String extractInstanceXml(String sessionData) {
        if (sessionData == null || sessionData.isBlank() || "{}".equals(sessionData.trim())) {
            return null;
        }

        String trimmed = sessionData.trim();
        if (trimmed.startsWith("<")) {
            return sessionData;
        }

        try {
            JsonNode root = objectMapper.readTree(sessionData);
            if (root == null || root.isNull()) {
                return null;
            }
            if (root.isTextual()) {
                return root.asText();
            }

            JsonNode instanceXml = root.get("instanceXml");
            return instanceXml != null && instanceXml.isTextual()
                    ? instanceXml.asText()
                    : null;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid web form session_data JSON", e);
        }
    }

    private void restoreFormPosition(WebFormSession session, FormDefinition formDef) {
        String screen = session.getCurrentScreen();
        if (screen == null || screen.isBlank() || "start".equals(screen)) {
            return;
        }

        final int targetEvent;
        try {
            targetEvent = Integer.parseInt(screen);
        } catch (NumberFormatException e) {
            log.warn(
                    "Ignoring unrecognized web form current_screen '{}' for session {}",
                    screen,
                    session.getId()
            );
            return;
        }

        String targetReference = session.getCurrentQuestionPath();
        int safetyCounter = 0;

        while (safetyCounter++ < 10000) {
            int event = formDef.getCurrentEvent();
            FormIndex index = formDef.getModel().getFormIndex();

            String currentReference = null;
            if (index != null && index.isInForm() && index.getReference() != null) {
                currentReference = index.getReference().toString();
            }

            if (event == targetEvent && Objects.equals(currentReference, targetReference)) {
                return;
            }

            if (event == FormEntryController.EVENT_END_OF_FORM) {
                break;
            }

            formDef.stepToNextEvent();
        }

        throw new IllegalStateException(
                "Unable to restore web form position for session " + session.getId()
                        + " (event=" + targetEvent
                        + ", reference=" + targetReference + ")"
        );
    }

    /**
     * Save an answer and re-evaluate relevance/calculations using JavaRosa.
     */
    public WebFormAnswer saveAnswer(UUID sessionId, String questionPath, Object value,
                                     String repeatPath, Integer repeatIndex) {
        return saveAnswer(sessionId, questionPath, null, value, repeatPath, repeatIndex);
    }

    /**
     * Save an answer using an optional instance-specific JavaRosa path. The
     * instance path is required to target nested repeats unambiguously; legacy
     * callers may omit it for non-repeat and single-level repeat questions.
     */
    public WebFormAnswer saveAnswer(UUID sessionId, String questionPath, String instancePath, Object value,
                                     String repeatPath, Integer repeatIndex) {
        WebFormSession session = getSession(sessionId);
        session.setLastActivityAt(OffsetDateTime.now());
        sessionRepository.save(session);

        // Recreate the JavaRosa runtime for this request and navigate that
        // same runtime to the question named by the API request.
        FormDefinition formDef = getFormDefinitionWithInstance(sessionId);

        if (!formDef.moveToQuestion(questionPath, instancePath, repeatPath, repeatIndex)) {
            throw new IllegalArgumentException(
                    "Question not found or not reachable: " + questionPath);
        }

        FormEntryPrompt prompt = formDef.getCurrentPrompt();
        
        // Parse the answer value using JavaRosa
        IAnswerData answerData;
        if (value == null) {
            answerData = null;
        } else {
            String stringValue = value.toString();
            answerData = formDef.parseAnswer(stringValue);
        }

        // Submit the answer through JavaRosa
        int result = formDef.answerQuestion(answerData);
        
        // Handle answer result
        if (result == FormEntryController.ANSWER_REQUIRED_BUT_EMPTY) {
            throw new IllegalArgumentException("Required question cannot be empty");
        } else if (result == FormEntryController.ANSWER_CONSTRAINT_VIOLATED) {
            String message = prompt != null ? prompt.getConstraintText(answerData) : "Constraint violated";
            throw new IllegalArgumentException(message);
        }

        // Create or update answer in database. Once navigation succeeds, use
        // JavaRosa as the authority for canonical and repeat context rather than
        // persisting client-supplied path metadata verbatim.
        String actualQuestionPath = formDef.getCurrentQuestionPath();
        String actualInstancePath = formDef.getCurrentQuestionInstancePath();
        String actualRepeatPath = formDef.getCurrentRepeatPath();
        Integer actualRepeatIndex = formDef.getCurrentRepeatIndex();

        Optional<WebFormAnswer> existing = Optional.empty();
        if (actualInstancePath != null && !actualInstancePath.isBlank()) {
            existing = answerRepository.findBySessionIdAndInstancePath(sessionId, actualInstancePath);
        }
        if (existing.isEmpty()) {
            existing = answerRepository.findBySessionAndQuestionAndRepeat(
                    sessionId,
                    actualQuestionPath != null ? actualQuestionPath : questionPath,
                    actualRepeatPath,
                    actualRepeatIndex);
        }

        // Get question info from JavaRosa prompt
        // QuestionDef doesn't have getName() - get name from TreeElement in instance
        final String questionName;
        if (prompt != null) {
            FormIndex index = prompt.getIndex();
            if (index != null) {
                TreeElement element = formDef.getFormDef().getMainInstance().resolveReference(index.getReference());
                if (element != null) {
                    questionName = element.getName();
                } else {
                    questionName = questionPath;
                }
            } else {
                questionName = questionPath;
            }
        } else {
            questionName = questionPath;
        }
        final int dataType = prompt != null ? prompt.getDataType() : -1;
        final int controlType = prompt != null ? prompt.getControlType() : -1;
        final boolean isRequired = prompt != null && prompt.isRequired();
        final boolean isReadOnly = prompt != null && prompt.isReadOnly();
        final String constraintText = prompt != null ? prompt.getConstraintText() : null;
        final String appearanceHint = prompt != null ? prompt.getAppearanceHint() : null;
        final String finalQuestionPath = actualQuestionPath != null ? actualQuestionPath : questionPath;
        final String finalRepeatPath = actualRepeatPath;
        final Integer finalRepeatIndex = actualRepeatIndex;

        WebFormAnswer answer = existing.orElseGet(() -> WebFormAnswer.builder()
                .session(session)
                .questionPath(finalQuestionPath)
                .instancePath(actualInstancePath)
                .questionName(questionName)
                .questionType(getQuestionTypeString(controlType, dataType))
                .repeatPath(finalRepeatPath)
                .repeatIndex(finalRepeatIndex)
                .isRelevant(true) // Will be updated by re-evaluation
                .isRequired(isRequired)
                .constraintMessage(constraintText)
                .answeredAt(OffsetDateTime.now())
                .build());

        // Upgrade legacy rows to the exact runtime location and JavaRosa-derived
        // repeat metadata as soon as they are touched.
        answer.setQuestionPath(finalQuestionPath);
        answer.setInstancePath(actualInstancePath);
        answer.setRepeatPath(actualRepeatPath);
        answer.setRepeatIndex(actualRepeatIndex);

        // Set value based on type
        setAnswerValue(answer, value, getQuestionTypeString(controlType, dataType));
        
        // Overloaded method for getting question type from control/data type
        // This is a helper method that maps JavaRosa control/data types to string
        // The actual implementation is below

        // Re-evaluate relevance and calculations using JavaRosa
        reEvaluateForm(session, formDef);

        // JavaRosa has already validated required/constraint rules in
        // answerQuestion(). Prefer the QuestionDef from the prompt that was
        // actually answered. Looking it up again by XPath is fragile for
        // repeat instances (especially the second and later multiplicities),
        // because QuestionDef describes the bind while the instance node is
        // multiplicity-specific.
        QuestionDef questionDef = prompt != null
                ? prompt.getQuestion()
                : formDef.getQuestion(
                        answer.getQuestionPath(),
                        answer.getRepeatPath(),
                        answer.getRepeatIndex());

        if (questionDef != null) {
            validateAnswer(answer, questionDef, formDef);
        } else {
            // Reaching this point means JavaRosa accepted the answer. Do not
            // leave a successfully accepted answer in the default "pending"
            // state merely because a secondary metadata lookup failed.
            answer.setValidationStatus("valid");
            answer.setConstraintMessage(null);
        }

        answer = answerRepository.save(answer);
        persistFormState(sessionId, formDef);
        return answer;
    }

    /**
     * Set answer value based on type.
     */
    private void setAnswerValue(WebFormAnswer answer, Object value, String type) {
        // A WebFormAnswer has several typed storage columns. Always clear the
        // previous representation first so editing/re-answering cannot leave a
        // stale value in another column.
        answer.setValueText(null);
        answer.setValueNumber(null);
        answer.setValueDate(null);
        answer.setValueBoolean(null);
        answer.setValueJson(null);

        if (value == null) {
            return;
        }

        String normalizedType = type == null ? "" : type;
        switch (normalizedType) {
            case "string", "text", "note", "select1", "select" -> answer.setValueText(value.toString());
            case "int", "integer" -> answer.setValueNumber(new java.math.BigDecimal(value.toString()));
            case "decimal", "double" -> answer.setValueNumber(new java.math.BigDecimal(value.toString()));
            case "date", "dateTime", "datetime", "time" -> {
                if (value instanceof OffsetDateTime) {
                    answer.setValueDate((OffsetDateTime) value);
                } else {
                    answer.setValueText(value.toString());
                }
            }
            case "boolean" -> answer.setValueBoolean(Boolean.valueOf(value.toString()));

            // ODK geographic answers are lexical XForm strings, for example:
            //   geopoint: 0.3476 32.5825 0 5
            //   geotrace: point;point;point
            // They are NOT JSON. Writing these strings to a jsonb column makes
            // PostgreSQL reject them with SQLSTATE 22P02. Binary answers are
            // likewise represented by their filename/reference string.
            case "geopoint", "geoshape", "geotrace", "binary" ->
                    answer.setValueText(value.toString());
            default -> answer.setValueText(value.toString());
        }
    }

    /**
     * Re-evaluate relevance and calculations for all questions.
     */
    private void reEvaluateForm(WebFormSession session, FormDefinition formDef) {
        List<WebFormAnswer> allAnswers = answerRepository.findBySessionId(session.getId());
        
        // Build answer map for XPath evaluation
        Map<String, Object> answerMap = buildAnswerMap(allAnswers);
        
        // Evaluate each question's relevance using FormDefinition's new methods
        for (WebFormAnswer answer : allAnswers) {
            QuestionDef qDef = formDef.getQuestion(answer.getQuestionPath(), answer.getRepeatPath(), answer.getRepeatIndex());
            if (qDef != null) {
                // Get the TreeElement for this question
                TreeElement element = formDef.getTreeElement(qDef);
                if (element != null) {
                    // Check relevance using XPath evaluation on the bind's relevant attribute
                    String relevantXPath = formDef.getRelevantExpression(element);
                    if (relevantXPath != null && !relevantXPath.isEmpty()) {
                        boolean isRelevant = evaluateRelevance(relevantXPath, answerMap, answer);
                        answer.setIsRelevant(isRelevant);
                    }
                }
            }
        }
        
        // Re-run calculations using FormDefinition's new methods
        for (QuestionDef calcDef : formDef.getCalculations()) {
            TreeElement element = formDef.getTreeElement(calcDef);
            if (element != null) {
                String calcXPath = formDef.getCalculateExpression(element);
                if (calcXPath != null && !calcXPath.isEmpty()) {
                    Object calculatedValue = evaluateCalculation(calcXPath, answerMap, session.getId());
                    if (calculatedValue != null) {
                        String calcPath = formDef.getQuestionPath(calcDef);
                        String calcRepeatPath = formDef.getRepeatPath(calcDef);
                        Integer calcRepeatIndex = formDef.getRepeatIndex(calcDef);
                        Optional<WebFormAnswer> calcAnswer = answerRepository.findBySessionAndQuestionAndRepeat(
                                session.getId(), calcPath, calcRepeatPath, calcRepeatIndex);
                        calcAnswer.ifPresent(a -> {
                            setAnswerValue(a, calculatedValue, formDef.getQuestionTypeString(calcDef));
                            answerRepository.save(a);
                        });
                    }
                }
            }
        }
        
        answerRepository.saveAll(allAnswers);
    }

    /**
     * Build answer map for XPath evaluation.
     */
    private Map<String, Object> buildAnswerMap(List<WebFormAnswer> answers) {
        Map<String, Object> map = new HashMap<>();
        for (WebFormAnswer answer : answers) {
            String key = buildAnswerKey(answer);
            Object value = getAnswerValue(answer);
            map.put(key, value);
        }
        return map;
    }

    private String buildAnswerKey(WebFormAnswer answer) {
        StringBuilder key = new StringBuilder();
        if (answer.getRepeatPath() != null && !answer.getRepeatPath().isEmpty()) {
            key.append(answer.getRepeatPath()).append("[");
            key.append(answer.getRepeatIndex() != null ? answer.getRepeatIndex() : 0).append("]");
        }
        key.append("/").append(answer.getQuestionName());
        return key.toString();
    }

    private Object getAnswerValue(WebFormAnswer answer) {
        if (answer.getValueText() != null) return answer.getValueText();
        if (answer.getValueNumber() != null) return answer.getValueNumber();
        if (answer.getValueDate() != null) return answer.getValueDate();
        if (answer.getValueBoolean() != null) return answer.getValueBoolean();
        if (answer.getValueJson() != null) return answer.getValueJson();
        return null;
    }

    /**
     * Evaluate relevance expression using JavaRosa.
     */
    private boolean evaluateRelevance(String expression, Map<String, Object> answerMap, WebFormAnswer currentAnswer) {
        try {
            // Use FormDefinition's XPath evaluation
            FormDefinition formDef = getFormDefinition(currentAnswer.getSession().getId());
            Object result = formDef.evaluateXPath(expression);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            return true; // Default to relevant if not a boolean
        } catch (Exception e) {
            log.warn("Failed to evaluate relevance expression: {}", expression, e);
            return true; // Default to relevant on error
        }
    }

    /**
     * Evaluate calculation expression using JavaRosa.
     */
    private Object evaluateCalculation(String expression, Map<String, Object> answerMap, UUID sessionId) {
        try {
            // Use FormDefinition's XPath evaluation
            FormDefinition formDef = getFormDefinition(sessionId);
            Object result = formDef.evaluateXPath(expression);
            return result;
        } catch (Exception e) {
            log.warn("Failed to evaluate calculation expression: {}", expression, e);
            return null;
        }
    }

    /**
     * Validate answer against constraints.
     */
    private void validateAnswer(WebFormAnswer answer, QuestionDef questionDef, FormDefinition formDef) {
        Object value = getAnswerValue(answer);
        
        // Required validation
        if (answer.getIsRequired() && (value == null || value.toString().isEmpty())) {
            answer.setValidationStatus("invalid");
            answer.setConstraintMessage("This field is required");
            return;
        }
        
        // Constraint validation - use bind's constraint expression
        // In JavaRosa v4.3.2, constraint is evaluated via XPath on the instance
        // We'll use the FormDefinition's XPath evaluation
        if (questionDef.getBind() != null && value != null) {
            // In JavaRosa v4.3.2, IDataReference doesn't have getBindAttributeValue
            // We'll skip constraint validation here and rely on JavaRosa's answerQuestion result
            // The constraint is already checked when answerQuestion is called
        }
        
        answer.setValidationStatus("valid");
        answer.setConstraintMessage(null);
    }

    private boolean evaluateConstraint(String expression, Object value, Map<String, Object> answerMap, UUID sessionId) {
        try {
            // Use FormDefinition's XPath evaluation
            FormDefinition formDef = getFormDefinition(sessionId);
            Object result = formDef.evaluateXPath(expression);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            return true; // Default to valid if not a boolean
        } catch (Exception e) {
            log.warn("Failed to evaluate constraint expression: {}", expression, e);
            return true; // Default to valid on error
        }
    }

    /**
     * Calculate progress from the persisted JavaRosa instance. Only currently
     * reachable, answerable questions are counted. Notes/labels are excluded.
     */
    @Transactional(readOnly = true)
    public FormProgress calculateProgress(UUID sessionId) {
        getSession(sessionId);
        FormDefinition formDef = getFormDefinitionWithInstance(sessionId);

        int answered = 0;
        int total = 0;
        int safetyCounter = 0;

        while (safetyCounter++ < 10000) {
            int event = formDef.getCurrentEvent();

            if (event == FormEntryController.EVENT_QUESTION) {
                int controlType = formDef.getCurrentControlType();
                boolean answerable = controlType != Constants.CONTROL_LABEL
                        && controlType != 9 // CONTROL_HIDDEN in JavaRosa 4.3.x
                        && !formDef.isCurrentReadOnly();
                if (answerable) {
                    total++;
                    String answer = formDef.getCurrentAnswerValue();
                    if (answer != null && !answer.isBlank()) {
                        answered++;
                    }
                }
            }

            if (event == FormEntryController.EVENT_END_OF_FORM) {
                break;
            }

            formDef.stepToNextEvent();
        }

        if (safetyCounter >= 10000) {
            throw new IllegalStateException("Unable to calculate form progress: navigation did not terminate");
        }

        int percent = total == 0 ? 0 : (int) Math.round((answered * 100.0d) / total);
        return new FormProgress(answered, total, Math.max(0, Math.min(100, percent)));
    }

    public record FormProgress(int answeredQuestions, int totalQuestions, int percent) {}

    /**
     * Finalize session and create submission.
     */
    public Submission finalizeSession(UUID sessionId) {
        WebFormSession session = getSession(sessionId);
        
        if (!"draft".equals(session.getStatus())
                && !"active".equals(session.getStatus())
                && !"completed".equals(session.getStatus())) {
            throw new IllegalStateException("Session cannot be finalized: " + session.getStatus());
        }

        // Rehydrate the exact JavaRosa instance persisted for this web-form
        // session. Serializing the JavaRosa instance preserves the original
        // instance structure, namespaces, groups and repeat multiplicities. A
        // hand-built XML document can look plausible while still being rejected
        // by JavaRosa as not matching the template form definition.
        FormDefinition formDef = getFormDefinitionWithInstance(sessionId);
        FormDefinition.ValidationResult validation = formDef.validate();
        if (!validation.isValid()) {
            throw new IllegalStateException(
                    "Form validation failed before finalization (outcome="
                            + validation.getOutcomeCode() + ")");
        }

        // Ensure the canonical ODK meta/instanceID exists before the instance is
        // finalized and serialized.
        formDef.getInstanceId();
        String submissionXml = formDef.finalizeAndSerialize();

        // Process submission using the existing service
        SubmissionProcessingService.ProcessedSubmission processed = 
            submissionProcessingService.processSubmission(submissionXml, session.getFormVersion(), 
                session.getAppUser(), submissionXml);
        
        // Create and persist the Submission before linking it to the session.
        // WebFormSession.submission is a normal entity association, so pointing it
        // at a transient Submission causes Hibernate to fail on the next flush.
        // `Submission.submittedBy` is the historical AppUser relationship used
        // by Collect/KoboCollect submissions. Web forms are normally filled by
        // platform Users, so preserve that actor in metadata as well. This
        // avoids a schema migration while keeping both submitter types visible
        // in the common submissions list/detail API.
        Map<String, Object> submitterMetadata = new LinkedHashMap<>();
        if (session.getAppUser() != null) {
            submitterMetadata.put("submittedByType", "APP_USER");
            submitterMetadata.put("submittedById", session.getAppUser().getId().toString());
            submitterMetadata.put("submittedByName", session.getAppUser().getUsername());
        } else if (session.getUser() != null) {
            submitterMetadata.put("submittedByType", "USER");
            submitterMetadata.put("submittedById", session.getUser().getId().toString());
            submitterMetadata.put("submittedByName", session.getUser().getUsername());
            submitterMetadata.put("submittedByDisplayName", session.getUser().getFullName());
        }

        Submission submission = Submission.builder()
                .form(session.getFormVersion().getForm())
                .formVersion(session.getFormVersion())
                .xmlContent(submissionXml)
                .jsonContent(processed.getJsonContent().toString())
                .instanceId(processed.getInstanceId())
                .submittedBy(session.getAppUser())
                .metadata(submitterMetadata)
                .submittedAt(Instant.now())
                .status("submitted")
                .build();

        submission = submissionRepository.saveAndFlush(submission);

        Map<String, Object> submissionEventDetails = new LinkedHashMap<>();
        submissionEventDetails.put("formVersionId", session.getFormVersion().getId().toString());
        submissionEventDetails.put("sessionId", session.getId().toString());
        if (!submitterMetadata.isEmpty()) {
            submissionEventDetails.putAll(submitterMetadata);
        }
        submission = submissionPersistenceService.persistProcessedData(
                submission,
                processed,
                formDef.getFormDef(),
                "Web Form",
                "RECEIVED",
                session.getAppUser() != null ? "APP_USER" : (session.getUser() != null ? "USER" : null),
                session.getAppUser() != null ? session.getAppUser().getId() : (session.getUser() != null ? session.getUser().getId() : null),
                submissionEventDetails);

        // Update session only after Submission has a persistent identity.
        session.setStatus("submitted");
        session.setSubmittedAt(OffsetDateTime.now());
        session.setSubmission(submission);
        sessionRepository.save(session);

        // Move attachments to submission
        moveAttachmentsToSubmission(session, submission);

        return submission;
    }

    /**
     * Move web form attachments to submission attachments.
     */
    private void moveAttachmentsToSubmission(WebFormSession session, Submission submission) {
        List<WebFormAttachment> attachments = attachmentRepository.findBySessionId(session.getId());

        for (WebFormAttachment attachment : attachments) {
            String targetPath = "submissions/" + submission.getId() + "/"
                    + UUID.randomUUID() + "_" + attachment.getFileName();

            try {
                byte[] content = storageService.read(attachment.getStoragePath());
                storageService.store(
                        new java.io.ByteArrayInputStream(content),
                        targetPath,
                        attachment.getStorageDriver());

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("questionPath", attachment.getQuestionPath());
                metadata.put("webFormAttachmentId", attachment.getId().toString());
                metadata.put(
                        "referencedInAnswers",
                        submission.getXmlContent() != null
                                && submission.getXmlContent().contains(attachment.getFileName()));

                SubmissionAttachment submissionAttachment = SubmissionAttachment.builder()
                        .submission(submission)
                        .fileName(attachment.getFileName())
                        .contentType(attachment.getContentType())
                        .fileSize(attachment.getFileSize())
                        .storagePath(targetPath)
                        .storageDriver(attachment.getStorageDriver())
                        .contentHash(attachment.getContentHash())
                        .metadata(metadata)
                        .build();

                submissionAttachmentRepository.saveAndFlush(submissionAttachment);

                // Only remove the temporary session copy after the submission
                // attachment metadata has been accepted by persistence.
                storageService.delete(attachment.getStoragePath());
            } catch (IOException | RuntimeException e) {
                try {
                    storageService.delete(targetPath);
                } catch (IOException cleanupError) {
                    log.warn(
                            "Failed to clean up submission attachment target: {}",
                            targetPath,
                            cleanupError);
                }

                throw new IllegalStateException(
                        "Failed to transfer web-form attachment '"
                                + attachment.getFileName()
                                + "' to submission "
                                + submission.getId(),
                        e);
            }
        }

        if (!attachments.isEmpty()) {
            attachmentRepository.deleteAll(attachments);
            attachmentRepository.flush();
        }
    }

    /**
     * Save attachment for a session.
     */
    public WebFormAttachment saveAttachment(UUID sessionId, UUID answerId, String questionPath,
                                             String fileName, String contentType, long fileSize,
                                             byte[] content) throws IOException {
        WebFormSession session = getSession(sessionId);

        String safeFileName = sanitizeAttachmentFileName(fileName);
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Attachment content must not be empty");
        }
        if (fileSize < 0 || fileSize != content.length) {
            throw new IllegalArgumentException(
                    "Attachment file size does not match uploaded content");
        }

        // Preserve the original filename in the XForm answer, while using a
        // unique storage object name so repeated questions can upload files
        // with identical client-side filenames without overwriting each other.
        String storagePath = "web-forms/" + sessionId + "/"
                + UUID.randomUUID() + "_" + safeFileName;
        storageService.store(
                new java.io.ByteArrayInputStream(content),
                storagePath,
                "local");

        WebFormAttachment attachment = WebFormAttachment.builder()
                .session(session)
                .answer(answerId != null ? answerRepository.findById(answerId).orElse(null) : null)
                .questionPath(questionPath)
                .fileName(safeFileName)
                .contentType(contentType)
                .fileSize(fileSize)
                .storagePath(storagePath)
                .contentHash(computeHash(content))
                .build();

        try {
            return attachmentRepository.save(attachment);
        } catch (RuntimeException e) {
            try {
                storageService.delete(storagePath);
            } catch (IOException cleanupError) {
                log.warn(
                        "Failed to clean up rejected web-form attachment: {}",
                        storagePath,
                        cleanupError);
            }
            throw e;
        }
    }

    private String sanitizeAttachmentFileName(String fileName) {
        if (fileName == null) {
            throw new IllegalArgumentException("Attachment filename is required");
        }

        String candidate = fileName.trim();
        if (candidate.isEmpty()
                || candidate.contains("..")
                || candidate.contains("/")
                || candidate.contains("\\")
                || candidate.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid attachment filename");
        }

        return candidate;
    }

    /**
     * Compute SHA-256 hash of content.
     */
    private String computeHash(byte[] content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Get the relevant expression from a QuestionDef's bind attributes.
     */
    private String getRelevantExpression(QuestionDef qDef) {
        if (qDef.getBind() != null) {
            // Use getBindAttributeValue with empty namespace for default
            // In JavaRosa v4.3.2, IDataReference doesn't have getBindAttributeValue
            // We need to use the bind's getConstraint() or similar
            // For now, return null and handle in evaluateRelevance
            return null;
        }
        return null;
    }

    /**
     * Get the calculate expression from a QuestionDef's bind attributes.
     */
    private String getCalculateExpression(QuestionDef qDef) {
        if (qDef.getBind() != null) {
            // In JavaRosa v4.3.2, IDataReference doesn't have getBindAttributeValue
            return null;
        }
        return null;
    }

    /**
     * Get the question path from a QuestionDef.
     */
    private String getQuestionPath(QuestionDef qDef) {
        if (qDef.getBind() != null && qDef.getBind().getReference() != null) {
            return qDef.getBind().getReference().toString();
        }
        // QuestionDef doesn't have getName() in v4.3.2
        // Use the bind reference or return empty
        return "";
    }

    /**
     * Get the repeat path from a QuestionDef.
     */
    private String getRepeatPath(QuestionDef qDef) {
        // In JavaRosa, repeat path is derived from the parent repeat group
        // This is a simplified implementation
        return "";
    }

    /**
     * Get the repeat index from a QuestionDef.
     */
    private Integer getRepeatIndex(QuestionDef qDef) {
        // Repeat index is runtime-specific, not stored in QuestionDef
        return 0;
    }

    /**
     * Get question type string from QuestionDef.
     */
    private String getQuestionTypeString(QuestionDef qDef) {
        if (qDef.getBind() != null) {
            // In JavaRosa v4.3.2, IDataReference doesn't have getBindAttributeValue
            return "string";
        }
        return "string";
    }

    /**
     * Get question type string from JavaRosa control and data types.
     */
    private String getQuestionTypeString(int controlType, int dataType) {
        // Map JavaRosa control types to question type strings
        // Control types: org.javarosa.core.model.Constants.CONTROL_*
        // Data types: org.javarosa.core.model.Constants.DATATYPE_*
        
        // Check control type first
        switch (controlType) {
            case 1: // CONTROL_INPUT
                // Check data type for input
                switch (dataType) {
                    case 1: // DATATYPE_TEXT
                        return "text";
                    case 2: // DATATYPE_INTEGER
                        return "int";
                    case 3: // DATATYPE_DECIMAL
                        return "decimal";
                    case 4: // DATATYPE_DATE
                        return "date";
                    case 5: // DATATYPE_TIME
                        return "time";
                    case 6: // DATATYPE_DATETIME
                        return "dateTime";
                    case 7: // DATATYPE_GEOPOINT
                        return "geopoint";
                    case 8: // DATATYPE_GEOSHAPE
                        return "geoshape";
                    case 9: // DATATYPE_GEOTRACE
                        return "geotrace";
                    case 10: // DATATYPE_BINARY
                        return "binary";
                    case 11: // DATATYPE_BARCODE
                        return "barcode";
                    default:
                        return "string";
                }
            case 2: // CONTROL_SELECT_ONE
                return "select1";
            case 3: // CONTROL_SELECT_MULTI
                return "select";
            case 4: // CONTROL_TRIGGER
                return "trigger";
            case 5: // CONTROL_UPLOAD
                return "binary";
            case 6: // CONTROL_GROUP
                return "group";
            case 7: // CONTROL_REPEAT
                return "repeat";
            case 8: // CONTROL_LABEL
                return "note";
            case 9: // CONTROL_HIDDEN
                return "hidden";
            default:
                // Fallback to data type
                switch (dataType) {
                    case 1: return "text";
                    case 2: return "int";
                    case 3: return "decimal";
                    case 4: return "date";
                    case 5: return "time";
                    case 6: return "dateTime";
                    case 7: return "geopoint";
                    case 8: return "geoshape";
                    case 9: return "geotrace";
                    case 10: return "binary";
                    case 11: return "barcode";
                    default: return "string";
                }
        }
    }

    /**
     * Update session status.
     */
    public WebFormSession updateSessionStatus(UUID sessionId, String status) {
        WebFormSession session = getSession(sessionId);
        session.setStatus(status);
        session.setLastActivityAt(OffsetDateTime.now());
        
        if ("completed".equals(status)) {
            session.setCompletedAt(OffsetDateTime.now());
        }
        
        return sessionRepository.save(session);
    }

    /**
     * Delete session and all associated data.
     */
    public void deleteSession(UUID sessionId) {
        WebFormSession session = getSession(sessionId);
        
        // Delete attachments (files)
        List<WebFormAttachment> attachments = attachmentRepository.findBySessionId(sessionId);
        for (WebFormAttachment attachment : attachments) {
            try {
                storageService.delete(attachment.getStoragePath());
            } catch (IOException e) {
                log.warn("Failed to delete attachment file: {}", attachment.getStoragePath(), e);
            }
        }
        
        // Delete from database (cascade will handle answers and attachments)
        sessionRepository.delete(session);
    }
}