package org.odk.platform.service;

import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.QuestionDef;
import org.javarosa.core.model.GroupDef;
import org.javarosa.core.model.IFormElement;
import org.javarosa.core.model.SelectChoice;
import org.javarosa.core.model.FormIndex;
import org.javarosa.core.model.ValidateOutcome;
import org.javarosa.core.model.DataType;
import org.javarosa.core.model.instance.FormInstance;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.form.api.FormEntryController;
import org.javarosa.form.api.FormEntryModel;
import org.javarosa.form.api.FormEntryPrompt;
import org.javarosa.form.api.FormEntryCaption;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.core.model.data.helper.AnswerDataUtil;
import org.javarosa.core.model.data.StringData;
import org.javarosa.core.util.PropertyUtils;
import org.javarosa.core.model.data.IntegerData;
import org.javarosa.core.model.data.DecimalData;
import org.javarosa.core.model.data.BooleanData;
import org.javarosa.core.model.data.DateData;
import org.javarosa.core.model.data.TimeData;
import org.javarosa.core.model.data.DateTimeData;
import org.javarosa.core.model.data.SelectOneData;
import org.javarosa.core.model.data.MultipleItemsData;
import org.javarosa.core.model.data.GeoPointData;
import org.javarosa.core.model.data.GeoShapeData;
import org.javarosa.core.model.data.GeoTraceData;
import org.javarosa.core.model.data.UncastData;
import org.javarosa.core.model.condition.Constraint;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.xpath.XPathConditional;
import org.javarosa.xpath.XPathNodeset;
import org.javarosa.xpath.XPathParseTool;
import org.javarosa.xpath.expr.XPathExpression;
import org.javarosa.model.xform.XFormSerializingVisitor;
import org.javarosa.model.xform.XFormsModule;
import org.javarosa.xform.util.XFormAnswerDataParser;
import org.javarosa.xform.util.XFormAnswerDataSerializer;
import org.javarosa.xform.parse.XFormParser;
import org.javarosa.core.model.instance.InstanceInitializationFactory;
import org.javarosa.model.xform.XPathReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wrapper around JavaRosa's FormDef that provides a clean API for the web form runtime.
 * This class encapsulates the JavaRosa form-entry session and provides methods for
 * navigation, answer submission, validation, and serialization.
 */
public class FormDefinition {
    
    private final FormDef formDef;
    private final FormEntryModel model;
    private final FormEntryController controller;
    private final String formVersionId;
    private final Map<String, BindMetadata> bindMetadataByPath;
    private final Map<String, String> mediaTypeByPath;
    private final Map<String, String> uploadAppearanceByPath;
    private final List<String> compatibilityWarnings;
    private static final Logger log = LoggerFactory.getLogger(FormDefinition.class);

    /*
     * Metadata-only scanner for XForm <bind> start tags.
     *
     * JavaRosa's parser accepts some legacy/non-strict form source that a JAXP
     * DOM parser rejects (for example a literal '<' inside a quoted XPath
     * attribute). These patterns deliberately scan only bind start tags and
     * quoted attributes; they do not resolve entities, DTDs, namespaces, or
     * external resources.
     */
    private static final Pattern BIND_TAG_PATTERN =
            Pattern.compile("<(?:[A-Za-z_][\\w.-]*:)?bind\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern UPLOAD_TAG_PATTERN =
            Pattern.compile("<(?:[A-Za-z_][\\w.-]*:)?upload\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ATTRIBUTE_PATTERN =
            Pattern.compile("([A-Za-z_][\\w:.-]*)\\s*=\\s*([\"'])(.*?)\\2", Pattern.DOTALL);

    /*
     * JavaRosa keeps the XForms answer/restoration factory in RestoreUtils as
     * module-level static state. XFormsModule.registerModule() installs that
     * factory (RestoreUtils.xfFact) and the required XPath prototypes.
     *
     * Register it once when this wrapper is loaded so parsing/restoring saved
     * instance XML works in both the application and unit tests.
     */
    static {
        new XFormsModule().registerModule();
    }
    
    private FormDefinition(FormDef formDef, String formVersionId, String xformXml, List<String> compatibilityWarnings) {
        this.formDef = formDef;
        this.formVersionId = formVersionId;
        this.bindMetadataByPath = parseBindMetadata(xformXml);
        this.mediaTypeByPath = parseUploadMediaTypes(xformXml);
        this.uploadAppearanceByPath = parseUploadAppearances(xformXml);
        this.compatibilityWarnings = compatibilityWarnings == null ? List.of() : List.copyOf(compatibilityWarnings);
        ensureInstanceId();
        this.model = new FormEntryModel(formDef);
        this.controller = new FormEntryController(model);
        // Initialize localizer if not already initialized
        if (formDef.getLocalizer() == null) {
            org.javarosa.core.services.locale.Localizer localizer = new org.javarosa.core.services.locale.Localizer();
            // Register default locales - use "en" and "fr" which are commonly used
            localizer.addAvailableLocale("en");
            localizer.addAvailableLocale("fr");
            localizer.setDefaultLocale("en");
            localizer.setLocale("en");
            formDef.setLocalizer(localizer);
        }
    }
    
    /**
     * Create a new FormDefinition from XForm XML for a new session.
     */
    public static FormDefinition createNew(String xformXml, String formVersionId) {
        return createNew(xformXml, formVersionId, Map.of());
    }

    /**
     * Create a new form while resolving form-version-scoped external datasets.
     * Dataset keys may be the configured dataset id, source filename, or basename.
     */
    public static FormDefinition createNew(String xformXml, String formVersionId, Map<String, byte[]> externalDatasets) {
        return createNew(xformXml, formVersionId, externalDatasets, List.of());
    }

    /** Create a new form with caller-supplied compatibility warnings from dataset providers. */
    public static FormDefinition createNew(String xformXml, String formVersionId,
                                           Map<String, byte[]> externalDatasets,
                                           List<String> initialCompatibilityWarnings) {
        XFormRuntimePreprocessor.PreparedXForm prepared =
                XFormRuntimePreprocessor.prepare(xformXml, externalDatasets);
        FormDef formDef = parseFormDef(prepared.xml());
        // Initialize with a fresh instance
        formDef.initialize(true, new InstanceInitializationFactory());
        // Ensure localizer is set with default locale
        if (formDef.getLocalizer() == null) {
            org.javarosa.core.services.locale.Localizer localizer = new org.javarosa.core.services.locale.Localizer();
            localizer.addAvailableLocale("en");
            localizer.addAvailableLocale("fr");
            localizer.setDefaultLocale("en");
            localizer.setLocale("en");
            formDef.setLocalizer(localizer);
        }
        return new FormDefinition(formDef, formVersionId, prepared.xml(),
                mergeCompatibilityWarnings(initialCompatibilityWarnings, prepared.warnings()));
    }
    
    /**
     * Create a FormDefinition from XForm XML and existing instance XML for resume.
     */
    public static FormDefinition createFromInstance(String xformXml, String instanceXml, String formVersionId) {
        return createFromInstance(xformXml, instanceXml, formVersionId, Map.of());
    }

    /** Resume a form while resolving the same external datasets used by the fresh runtime. */
    public static FormDefinition createFromInstance(String xformXml, String instanceXml, String formVersionId,
                                                    Map<String, byte[]> externalDatasets) {
        return createFromInstance(xformXml, instanceXml, formVersionId, externalDatasets, List.of());
    }

    /** Resume a form with caller-supplied compatibility warnings from dataset providers. */
    public static FormDefinition createFromInstance(String xformXml, String instanceXml, String formVersionId,
                                                    Map<String, byte[]> externalDatasets,
                                                    List<String> initialCompatibilityWarnings) {
        XFormRuntimePreprocessor.PreparedXForm prepared =
                XFormRuntimePreprocessor.prepare(xformXml, externalDatasets);
        FormDef formDef = parseFormDef(prepared.xml(), instanceXml);
        formDef.initialize(false, new InstanceInitializationFactory());
        // Ensure localizer is set with default locale
        if (formDef.getLocalizer() == null) {
            org.javarosa.core.services.locale.Localizer localizer = new org.javarosa.core.services.locale.Localizer();
            localizer.addAvailableLocale("en");
            localizer.addAvailableLocale("fr");
            localizer.setDefaultLocale("en");
            localizer.setLocale("en");
            formDef.setLocalizer(localizer);
        }
        return new FormDefinition(formDef, formVersionId, prepared.xml(),
                mergeCompatibilityWarnings(initialCompatibilityWarnings, prepared.warnings()));
    }

    private static List<String> mergeCompatibilityWarnings(List<String> first, List<String> second) {
        LinkedHashSet<String> warnings = new LinkedHashSet<>();
        if (first != null) warnings.addAll(first);
        if (second != null) warnings.addAll(second);
        return new ArrayList<>(warnings);
    }
    
    /**
     * JavaRosa's XFormParser protects parsing with a process-global, non-blocking
     * lock. If two web requests parse at the same time, one parser immediately
     * throws "Another XForm is being parsed!". The web UI intentionally loads
     * progress and field-list data in parallel, so every application entry point
     * must serialize JavaRosa parsing before entering XFormParser.parse().
     *
     * Synchronizing on XFormParser.class gives FormDefinition and
     * JavaRosaFormLoader one shared application-owned monitor without changing
     * JavaRosa itself. Do not replace this with a FormDefinition-local lock: the
     * loader and web runtime must coordinate with each other as well.
     */
    private static FormDef parseFormDef(String xformXml) {
        synchronized (XFormParser.class) {
            try {
                return new XFormParser(new StringReader(xformXml)).parse();
            } catch (XFormParser.ParseException e) {
                String detail = e.getMessage() == null ? "JavaRosa parse error" : e.getMessage();
                log.warn("JavaRosa rejected XForm: {}", detail, e);
                throw new IllegalArgumentException("Invalid ODK XForm XML: " + detail, e);
            } catch (RuntimeException e) {
                String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("JavaRosa failed to parse XForm: {}", detail, e);
                throw new IllegalArgumentException("Invalid ODK XForm XML: " + detail, e);
            }
        }
    }
    
    private static FormDef parseFormDef(String xformXml, String instanceXml) {
        synchronized (XFormParser.class) {
            try {
                return new XFormParser(new StringReader(xformXml), new StringReader(instanceXml)).parse();
            } catch (XFormParser.ParseException e) {
                String detail = e.getMessage() == null ? "JavaRosa parse error" : e.getMessage();
                log.warn("JavaRosa rejected saved XForm instance: {}", detail, e);
                throw new IllegalArgumentException(
                        "Invalid ODK XForm XML or instance XML: " + detail,
                        e);
            } catch (RuntimeException e) {
                String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("JavaRosa failed to restore saved XForm instance: {}", detail, e);
                throw new IllegalArgumentException(
                        "Invalid ODK XForm XML or instance XML: " + detail,
                        e);
            }
        }
    }
    
    public String getFormVersionId() {
        return formVersionId;
    }

    /** Compatibility/runtime warnings that should be shown rather than silently ignored. */
    public List<String> getCompatibilityWarnings() {
        return compatibilityWarnings;
    }
    
    public FormDef getFormDef() {
        return formDef;
    }
    
    public FormEntryModel getModel() {
        return model;
    }
    
    public FormEntryController getController() {
        return controller;
    }
    
    public FormInstance getInstance() {
        return formDef.getMainInstance();
    }
    
    /**
     * Get the current question prompt, or null if not on a question.
     */
    public FormEntryPrompt getCurrentPrompt() {
        if (model.getEvent() != FormEntryController.EVENT_QUESTION) {
            return null;
        }
        return model.getQuestionPrompt();
    }

    /**
     * Navigate this JavaRosa form-entry instance to the requested question.
     *
     * Each web request reconstructs its own FormDefinition, so callers cannot
     * depend on the FormIndex of a different wrapper created earlier.
     */
    public boolean moveToQuestion(String questionPath, String repeatPath, Integer repeatIndex) {
        return moveToQuestion(questionPath, null, repeatPath, repeatIndex);
    }

    /**
     * Navigate to a question, preferring the exact JavaRosa instance path when
     * supplied. The instance path carries every multiplicity in a nested repeat
     * hierarchy, so it can distinguish, for example, member 2 / visit 1 from
     * member 1 / visit 1.
     *
     * The repeatPath/repeatIndex pair is retained as a backwards-compatible
     * fallback for simple, single-level repeats.
     */
    public boolean moveToQuestion(
            String questionPath,
            String instancePath,
            String repeatPath,
            Integer repeatIndex) {
        if (questionPath == null || questionPath.isBlank()) {
            return false;
        }

        int event = model.getEvent();
        int safetyCounter = 0;

        while (safetyCounter++ < 10000) {
            if (event == FormEntryController.EVENT_QUESTION) {
                FormEntryPrompt prompt = model.getQuestionPrompt();
                if (matchesQuestionPath(
                        prompt,
                        questionPath,
                        instancePath,
                        repeatPath,
                        repeatIndex)) {
                    return true;
                }
            }

            if (event == FormEntryController.EVENT_END_OF_FORM) {
                return false;
            }

            event = controller.stepToNextEvent();
        }

        throw new IllegalStateException(
                "Exceeded form navigation safety limit while locating question: " + questionPath);
    }

    private boolean matchesQuestionPath(
            FormEntryPrompt prompt,
            String questionPath,
            String instancePath,
            String repeatPath,
            Integer repeatIndex) {

        if (prompt == null
                || prompt.getIndex() == null
                || prompt.getIndex().getReference() == null) {
            return false;
        }

        TreeReference reference = prompt.getIndex().getReference();

        String requestedInstancePath = normalizeQuestionPath(instancePath);
        if (!requestedInstancePath.isEmpty()) {
            String actualInstancePath = normalizeQuestionPath(referenceToApiInstancePath(reference));
            return actualInstancePath.equals(requestedInstancePath);
        }

        String actualPath = normalizeQuestionPath(referenceToPath(reference));
        String requestedQuestion = normalizeQuestionPath(questionPath);
        String requestedRepeat = normalizeQuestionPath(repeatPath);

        String requestedPath;
        if (!requestedRepeat.isEmpty()
                && !requestedQuestion.startsWith(requestedRepeat + "/")) {
            requestedPath = requestedRepeat + "/" + requestedQuestion;
        } else {
            requestedPath = requestedQuestion;
        }

        boolean pathMatches = actualPath.equals(requestedPath)
                || actualPath.endsWith("/" + requestedPath);

        // Simple API paths such as "name" are allowed for a non-repeat field.
        if (!pathMatches) {
            pathMatches = requestedRepeat.isEmpty()
                    && !requestedQuestion.contains("/")
                    && actualPath.endsWith("/" + requestedQuestion);
        }

        if (!pathMatches) {
            return false;
        }

        // Repeated questions share the same bind path across all instances.
        // When the API supplies a repeat index, require the runtime index to
        // match so an answer for repetition N cannot overwrite repetition 0.
        if (repeatIndex != null) {
            Integer currentRepeatIndex = getCurrentRepeatIndex();
            return repeatIndex.equals(currentRepeatIndex);
        }

        return true;
    }

    private String referenceToPath(TreeReference reference) {
        StringBuilder path = new StringBuilder();

        for (int i = 0; i < reference.size(); i++) {
            if (i > 0) {
                path.append('/');
            }
            path.append(reference.getName(i));
        }

        return path.toString();
    }

    private String normalizeQuestionPath(String path) {
        if (path == null) {
            return "";
        }

        String normalized = path.trim().replace('\\', '/');

        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }
    
    /**
     * Get the current question path in API form (root instance name removed).
     */
    public String getCurrentQuestionPath() {
        if (model.getEvent() != FormEntryController.EVENT_QUESTION) {
            return null;
        }

        FormEntryPrompt prompt = model.getQuestionPrompt();
        if (prompt == null || prompt.getIndex() == null) {
            return null;
        }

        return referenceToApiPath(prompt.getIndex().getReference());
    }

    /**
     * Return an instance-specific question path that preserves all JavaRosa
     * multiplicities. Unlike the canonical bind path, this remains unique
     * across nested repeat ancestors.
     */
    public String getCurrentQuestionInstancePath() {
        if (model.getEvent() != FormEntryController.EVENT_QUESTION) {
            return null;
        }

        FormEntryPrompt prompt = model.getQuestionPrompt();
        if (prompt == null || prompt.getIndex() == null) {
            return null;
        }

        return referenceToApiInstancePath(prompt.getIndex().getReference());
    }

    /**
     * Get the current/enclosing repeat path in API form.
     */
    public String getCurrentRepeatPath() {
        RepeatContext context = getCurrentOrEnclosingRepeatContext();
        return context != null
                ? referenceToApiPath(context.index().getReference())
                : null;
    }

    private String referenceToApiPath(TreeReference reference) {
        if (reference == null) {
            return null;
        }

        String path = normalizeQuestionPath(referenceToPath(reference));
        TreeElement root = formDef.getMainInstance().getRoot();
        String rootName = root != null ? root.getName() : null;

        if (rootName == null || rootName.isBlank()) {
            return path;
        }

        if (path.equals(rootName)) {
            return "";
        }

        String rootPrefix = rootName + "/";
        return path.startsWith(rootPrefix)
                ? path.substring(rootPrefix.length())
                : path;
    }

    private String referenceToApiInstancePath(TreeReference reference) {
        if (reference == null) {
            return null;
        }

        String path = normalizeQuestionPath(reference.toString(true));
        TreeElement root = formDef.getMainInstance().getRoot();
        String rootName = root != null ? root.getName() : null;

        if (rootName == null || rootName.isBlank()) {
            return path;
        }

        int slash = path.indexOf('/');
        String firstSegment = slash >= 0 ? path.substring(0, slash) : path;
        boolean rootSegment = firstSegment.equals(rootName)
                || (firstSegment.startsWith(rootName + "[") && firstSegment.endsWith("]"));

        if (!rootSegment) {
            return path;
        }

        return slash >= 0 ? path.substring(slash + 1) : "";
    }

    /**
     * Get the current group/repeat caption, or null if not on a group/repeat.
     */
    public FormEntryCaption getCurrentCaption() {
        return model.getCaptionPrompt();
    }
    
    /**
     * Get the current event type.
     */
    public int getCurrentEvent() {
        return model.getEvent();
    }
    
    /**
     * Move to the next event (question, group, repeat, etc.).
     * Returns the event type constant.
     */
    public int stepToNextEvent() {
        return controller.stepToNextEvent();
    }

    /**
     * Move to the next screen a form filler should actually see. JavaRosa emits
     * structural GROUP events as part of traversal. ODK Collect skips ordinary
     * groups and only treats appearance=field-list groups as screens. Keep
     * repeat events visible because this web client exposes repeat management
     * controls there.
     */
    public int stepToNextScreenEvent() {
        int event = getCurrentEvent();
        int safetyCounter = 0;

        while (safetyCounter++ < 10000) {
            if (event == FormEntryController.EVENT_END_OF_FORM) {
                return event;
            }
            event = stepToNextEvent();
            if (isRenderableScreenEvent(event)) {
                return event;
            }
        }

        throw new IllegalStateException("Unable to find the next renderable form screen");
    }

    /**
     * Move to the previous visible screen, skipping structural group events in
     * the same way as forward navigation.
     */
    public int stepToPreviousScreenEvent() {
        int event = getCurrentEvent();
        int safetyCounter = 0;

        while (safetyCounter++ < 10000) {
            if (event == FormEntryController.EVENT_BEGINNING_OF_FORM) {
                return event;
            }
            event = stepToPreviousEvent();
            if (isRenderableScreenEvent(event)) {
                return event;
            }
        }

        throw new IllegalStateException("Unable to find the previous renderable form screen");
    }

    /**
     * If raw JavaRosa traversal has just left a field-list on a structural
     * group event, continue until the next/previous user-facing screen.
     */
    public int normalizeCurrentScreen(boolean forward) {
        int event = getCurrentEvent();
        if (isRenderableScreenEvent(event)) {
            return event;
        }
        return forward ? stepToNextScreenEvent() : stepToPreviousScreenEvent();
    }

    private boolean isRenderableScreenEvent(int event) {
        if (event == FormEntryController.EVENT_GROUP) {
            return isCurrentFieldListGroup();
        }

        return event == FormEntryController.EVENT_QUESTION
                || event == FormEntryController.EVENT_REPEAT
                || event == FormEntryController.EVENT_REPEAT_JUNCTURE
                || event == FormEntryController.EVENT_PROMPT_NEW_REPEAT
                || event == FormEntryController.EVENT_BEGINNING_OF_FORM
                || event == FormEntryController.EVENT_END_OF_FORM;
    }
    
    /**
     * Move to the previous event.
     * Returns the event type constant.
     */
    public int stepToPreviousEvent() {
        return controller.stepToPreviousEvent();
    }
    
    /**
     * Check if we're at the beginning of the form.
     */
    public boolean isAtBeginningOfForm() {
        return model.getEvent() == FormEntryController.EVENT_BEGINNING_OF_FORM;
    }
    
    /**
     * Check if we're at the end of the form.
     */
    public boolean isAtEndOfForm() {
        return model.getEvent() == FormEntryController.EVENT_END_OF_FORM;
    }
    
    /**
     * Check if the current index is relevant (visible).
     */
    public boolean isIndexRelevant(FormIndex index) {
        return model.isIndexRelevant(index);
    }
    
    /**
     * Submit an answer for the current question.
     * Returns the answer result code.
     */
    public int answerQuestion(IAnswerData answer) {
        FormEntryPrompt prompt = model.getQuestionPrompt();
        if (prompt == null) {
            throw new IllegalStateException("Not currently on a question");
        }
        return controller.answerQuestion(prompt.getIndex(), answer, true);
    }
    
    /**
     * Parse a string value into an IAnswerData based on the current question's data type.
     */
    public IAnswerData parseAnswer(String rawValue) {
        FormEntryPrompt prompt = model.getQuestionPrompt();
        if (prompt == null) {
            throw new IllegalStateException("Not currently on a question");
        }
        
        String normalized = (rawValue == null || rawValue.trim().isEmpty()) ? null : rawValue.trim();
        
        if (normalized == null) {
            return null;
        }
        
        return XFormAnswerDataParser.getAnswerData(normalized, prompt.getDataType(), prompt.getQuestion());
    }
    
    /**
     * Create an IAnswerData from a string value and data type hint.
     */
    public static IAnswerData createAnswer(String value, int dataType, QuestionDef questionDef) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return XFormAnswerDataParser.getAnswerData(value.trim(), dataType, questionDef);
    }
    
    /**
     * Get the current question's data type.
     */
    public int getCurrentDataType() {
        FormEntryPrompt prompt = model.getQuestionPrompt();
        return prompt != null ? prompt.getDataType() : -1;
    }
    
    /**
     * Get the current question's control type.
     */
    public int getCurrentControlType() {
        FormEntryPrompt prompt = model.getQuestionPrompt();
        return prompt != null ? prompt.getControlType() : -1;
    }
    
    /**
     * Get the current question's required state.
     */
    public boolean isCurrentRequired() {
        FormEntryPrompt prompt = model.getQuestionPrompt();
        return prompt != null && prompt.isRequired();
    }
    
    /**
     * Get the current question's readonly state.
     */
    public boolean isCurrentReadOnly() {
        FormEntryPrompt prompt = model.getQuestionPrompt();
        return prompt != null && prompt.isReadOnly();
    }
    
    /**
     * Get the current question's constraint text.
     *
     * JavaRosa materializes the standard XForms "constraint" bind into the
     * TreeElement's Constraint object rather than the additional bind-attribute
     * map. Prefer the localized constraint message when present, otherwise
     * return the original XPath expression.
     */
    public String getCurrentConstraintText() {
        if (model.getEvent() != FormEntryController.EVENT_QUESTION) {
            return null;
        }

        FormEntryPrompt prompt = model.getQuestionPrompt();
        if (prompt == null) {
            return null;
        }

        String message = prompt.getConstraintText();
        if (message != null && !message.isBlank()) {
            return message;
        }

        FormIndex index = prompt.getIndex();
        if (index == null || index.getReference() == null) {
            return null;
        }

        TreeElement element = formDef.getMainInstance().resolveReference(index.getReference());
        return getConstraintXPath(element);
    }
    
    /**
     * Get the current question's appearance hint.
     */
    public String getCurrentAppearanceHint() {
        FormEntryPrompt prompt = model.getQuestionPrompt();
        if (prompt == null) {
            return null;
        }

        String appearance = prompt.getAppearanceHint();
        if (appearance != null && !appearance.isBlank()) {
            return appearance;
        }

        // Upload appearances such as `signature` are behaviorally important
        // to the web renderer. Keep the same source-level fallback used for
        // upload mediatype so forms remain usable even when JavaRosa does not
        // expose the attribute through FormEntryPrompt.
        if (prompt.getIndex() != null && prompt.getIndex().getReference() != null) {
            String bindPath = normalizeBindPath(referenceToPath(prompt.getIndex().getReference()));
            appearance = uploadAppearanceByPath.get(bindPath);
            if (appearance != null && !appearance.isBlank()) {
                return appearance;
            }
        }

        return uploadAppearanceByPath.get(normalizeBindPath(getCurrentQuestionPath()));
    }

    /**
     * Return the media type declared by the current upload control, when any.
     *
     * JavaRosa 4.3.2 does not consistently retain the upload control's
     * {@code mediatype} as a QuestionDef additional attribute. The constructor
     * therefore also indexes upload controls directly from the XForm source.
     * Use the full JavaRosa bind reference first (for example, data/photo),
     * because the public API question path intentionally removes the root
     * instance name (for example, photo).
     */
    public String getCurrentMediaType() {
        FormEntryPrompt prompt = model.getQuestionPrompt();
        if (prompt == null || prompt.getQuestion() == null) {
            return null;
        }

        String mediaType = prompt.getQuestion().getAdditionalAttribute(null, "mediatype");
        if (mediaType == null || mediaType.isBlank()) {
            mediaType = prompt.getQuestion().getAdditionalAttribute(null, "mediaType");
        }
        if (mediaType != null && !mediaType.isBlank()) {
            return mediaType;
        }

        if (prompt.getIndex() != null && prompt.getIndex().getReference() != null) {
            String bindPath = normalizeBindPath(referenceToPath(prompt.getIndex().getReference()));
            mediaType = mediaTypeByPath.get(bindPath);
            if (mediaType != null && !mediaType.isBlank()) {
                return mediaType;
            }
        }

        // Backward-compatible fallback for callers/forms whose upload metadata
        // was indexed without the root instance segment.
        String currentPath = normalizeBindPath(getCurrentQuestionPath());
        return mediaTypeByPath.get(currentPath);
    }
    
    /**
     * Get the current question's label text.
     */
    public String getCurrentLabel() {
        FormEntryPrompt prompt = model.getQuestionPrompt();
        return prompt != null ? prompt.getQuestionText() : null;
    }
    
    /**
     * Get the current question's hint text.
     */
    public String getCurrentHint() {
        FormEntryPrompt prompt = model.getQuestionPrompt();
        return prompt != null ? prompt.getHelpText() : null;
    }
    
    /**
     * Get the current question's select choices.
     */
    public List<SelectChoice> getCurrentChoices() {
        FormEntryPrompt prompt = model.getQuestionPrompt();
        if (prompt == null) {
            return List.of();
        }
        
        List<SelectChoice> choices = new ArrayList<>();
        List<org.javarosa.core.model.SelectChoice> selectChoices = prompt.getSelectChoices();
        if (selectChoices != null) {
            for (org.javarosa.core.model.SelectChoice sc : selectChoices) {
                choices.add(new SelectChoice(sc.getValue(), sc.getLabelInnerText()));
            }
        }
        return choices;
    }
    
    /**
     * Return a prompt-media reference from JavaRosa itext, normalized to the
     * attachment filename stored with the form version. Typical XForm values
     * are jr://audio/file.wav, jr://images/file.png and jr://video/file.mp4.
     */
    public String getCurrentPromptMediaFile(String mediaForm) {
        FormEntryPrompt prompt = model.getQuestionPrompt();
        if (prompt == null || mediaForm == null || mediaForm.isBlank()) {
            return null;
        }

        String reference = prompt.getSpecialFormQuestionText(mediaForm);
        if (reference == null || reference.isBlank()) {
            return null;
        }

        String normalized = reference.trim();
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        int fragmentIndex = normalized.indexOf('#');
        if (fragmentIndex >= 0) {
            normalized = normalized.substring(0, fragmentIndex);
        }
        normalized = normalized.replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        if (slashIndex >= 0 && slashIndex < normalized.length() - 1) {
            normalized = normalized.substring(slashIndex + 1);
        }
        return normalized.isBlank() ? null : normalized;
    }

    /**
     * Return the current answer in canonical XForms storage format.
     *
     * FormEntryPrompt#getAnswerText() is intended for display and therefore
     * converts select values to labels and dates/times to localized text. The
     * web renderer needs the persisted value instead (for example a select
     * value rather than its label, and yyyy-MM-dd rather than a localized
     * date). Using JavaRosa's own XForm serializer keeps the browser and the
     * mobile clients on the same representation.
     */
    public String getCurrentAnswerValue() {
        FormEntryPrompt prompt = model.getQuestionPrompt();
        if (prompt == null) {
            return null;
        }

        IAnswerData answer = prompt.getAnswerValue();
        if (answer == null) {
            return null;
        }

        Object serialized = new XFormAnswerDataSerializer()
                .serializeAnswerData(answer, prompt.getDataType());
        return serialized == null ? null : serialized.toString();
    }

    /**
     * Display-oriented answer text retained for review/diagnostic callers.
     */
    public String getCurrentAnswerText() {
        FormEntryPrompt prompt = model.getQuestionPrompt();
        return prompt != null ? prompt.getAnswerText() : null;
    }
    
    /**
     * Get the current question's index (FormIndex), or null when the current
     * event is not a question.
     */
    public FormIndex getCurrentQuestionIndex() {
        if (model.getEvent() != FormEntryController.EVENT_QUESTION) {
            return null;
        }

        FormEntryPrompt prompt = model.getQuestionPrompt();
        return prompt != null ? prompt.getIndex() : null;
    }

    /**
     * Get the current FormIndex regardless of event type.
     */
    public FormIndex getCurrentFormIndex() {
        return model.getFormIndex();
    }

    /**
     * Convert an arbitrary JavaRosa form index to the canonical API question
     * path (root instance name removed). Used to turn validation failures into
     * actionable field-level errors for the web renderer.
     */
    public String getApiPath(FormIndex index) {
        if (index == null || index.getReference() == null) {
            return null;
        }
        return referenceToApiPath(index.getReference());
    }

    /**
     * Convert an arbitrary JavaRosa form index to an instance-specific API path
     * that preserves repeat multiplicities.
     */
    public String getApiInstancePath(FormIndex index) {
        if (index == null || index.getReference() == null) {
            return null;
        }
        return referenceToApiInstancePath(index.getReference());
    }

    /**
     * Get the current question's number (1-based index among all questions).
     * This is useful for display purposes.
     */
    public int getCurrentQuestionNumber() {
        if (model.getEvent() != FormEntryController.EVENT_QUESTION) {
            return 0;
        }

        FormEntryPrompt prompt = model.getQuestionPrompt();
        if (prompt == null || prompt.getIndex() == null) {
            return 0;
        }

        TreeReference targetRef = prompt.getIndex().getReference();
        if (targetRef == null) {
            return 0;
        }

        int[] questionCounter = {0};
        return findQuestionNumber(formDef.getChildren(), targetRef, questionCounter);
    }

    /**
     * Traverse the form-definition tree in display order and return the
     * 1-based number of the question whose bind reference matches targetRef.
     *
     * QuestionDef does not expose a FormIndex in JavaRosa 4.3.2, so matching
     * by its bind TreeReference is the appropriate stable identifier. Repeat
     * multiplicities are intentionally ignored because this number represents
     * the question definition's position in the form, not a repeat instance.
     */
    private int findQuestionNumber(
            List<?> children,
            TreeReference targetRef,
            int[] questionCounter) {

        if (children == null) {
            return 0;
        }

        for (Object child : children) {
            if (child instanceof QuestionDef) {
                QuestionDef question = (QuestionDef) child;
                questionCounter[0]++;

                if (question.getBind() != null) {
                    Object bindRefObject = question.getBind().getReference();
                    if (bindRefObject instanceof TreeReference) {
                        TreeReference bindRef = (TreeReference) bindRefObject;
                        if (referencesEqual(bindRef, targetRef)) {
                            return questionCounter[0];
                        }
                    }
                }
            } else if (child instanceof GroupDef) {
                GroupDef group = (GroupDef) child;
                int found = findQuestionNumber(
                        group.getChildren(),
                        targetRef,
                        questionCounter
                );
                if (found > 0) {
                    return found;
                }
            }
        }

        return 0;
    }
    
    /**
     * Return the complete enclosing group hierarchy for the current event.
     * This is used by the web renderer to preserve nested group structure
     * without re-implementing XForm traversal in the browser.
     */
    public List<GroupContext> getCurrentGroupHierarchy() {
        FormIndex currentIndex = model.getFormIndex();
        if (currentIndex == null || !currentIndex.isInForm()) {
            return Collections.emptyList();
        }

        FormEntryCaption[] captions = model.getCaptionHierarchy(currentIndex);
        if (captions == null || captions.length == 0) {
            return Collections.emptyList();
        }

        List<GroupContext> hierarchy = new ArrayList<>();
        for (FormEntryCaption caption : captions) {
            FormIndex captionIndex = caption.getIndex();
            if (captionIndex == null || !captionIndex.isInForm()) {
                continue;
            }

            IFormElement element = formDef.getChild(captionIndex);
            if (!(element instanceof GroupDef group)) {
                continue;
            }

            TreeReference reference = captionIndex.getReference();
            Integer multiplicity = null;
            if (group.getRepeat() && reference != null && reference.size() > 0) {
                int value = reference.getMultLast();
                if (value >= 0) {
                    multiplicity = value;
                }
            }

            hierarchy.add(new GroupContext(
                    caption.getLongText(),
                    referenceToApiPath(reference),
                    referenceToApiInstancePath(reference),
                    group.getRepeat(),
                    multiplicity,
                    hierarchy.size(),
                    group.getAppearanceAttr()
            ));
        }

        return Collections.unmodifiableList(hierarchy);
    }

    /**
     * Get the current group/repeat caption text.
     */
    public String getCurrentCaptionText() {
        FormEntryCaption caption = model.getCaptionPrompt();
        return caption != null ? caption.getLongText() : null;
    }

    /**
     * Return the appearance assigned to the current group/repeat. JavaRosa
     * exposes group appearances on IFormElement just like question appearances.
     */
    public String getCurrentGroupAppearanceHint() {
        FormIndex index = model.getFormIndex();
        if (index == null || !index.isInForm()) {
            return null;
        }
        IFormElement element = formDef.getChild(index);
        return element instanceof GroupDef ? element.getAppearanceAttr() : null;
    }

    public boolean isCurrentFieldListGroup() {
        return getCurrentOrEnclosingFieldListContext() != null;
    }

    /**
     * Return the path of the nearest enclosing appearance=field-list group.
     * This works both while JavaRosa is positioned on the group event itself
     * and while it is positioned on one of that group's child questions.
     */
    public String getCurrentFieldListPath() {
        FieldListContext context = getCurrentOrEnclosingFieldListContext();
        return context == null ? null : referenceToApiPath(context.index().getReference());
    }

    public String getCurrentFieldListInstancePath() {
        FieldListContext context = getCurrentOrEnclosingFieldListContext();
        return context == null ? null : referenceToApiInstancePath(context.index().getReference());
    }

    private FieldListContext getCurrentOrEnclosingFieldListContext() {
        FormIndex currentIndex = model.getFormIndex();
        if (currentIndex == null || !currentIndex.isInForm()) {
            return null;
        }

        // When positioned directly on a group event, inspect that group first.
        IFormElement currentElement = formDef.getChild(currentIndex);
        if (currentElement instanceof GroupDef currentGroup
                && hasAppearanceToken(currentGroup.getAppearanceAttr(), "field-list")) {
            return new FieldListContext(currentIndex, currentGroup);
        }

        // Answer saves persist the exact child question position. A later page
        // refresh therefore needs to recover the enclosing field-list group from
        // JavaRosa's caption hierarchy instead of requiring the GROUP event.
        FormEntryCaption[] captions = model.getCaptionHierarchy(currentIndex);
        if (captions == null) {
            return null;
        }
        for (int i = captions.length - 1; i >= 0; i--) {
            FormIndex captionIndex = captions[i].getIndex();
            if (captionIndex == null || !captionIndex.isInForm()) {
                continue;
            }
            IFormElement element = formDef.getChild(captionIndex);
            if (element instanceof GroupDef group
                    && hasAppearanceToken(group.getAppearanceAttr(), "field-list")) {
                return new FieldListContext(captionIndex, group);
            }
        }
        return null;
    }

    private boolean hasAppearanceToken(String appearance, String expected) {
        if (appearance == null || appearance.isBlank()) {
            return false;
        }
        for (String token : appearance.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (expected.equals(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Return all non-repeat question targets contained by the current or
     * enclosing appearance=field-list group. Nested non-repeat groups are
     * flattened onto the same page; nested repeats remain separate navigation
     * structures.
     */
    public List<FieldListQuestionTarget> getCurrentFieldListQuestionTargets() {
        FieldListContext context = getCurrentOrEnclosingFieldListContext();
        if (context == null || context.index().getReference() == null) {
            return Collections.emptyList();
        }

        String groupPath = referenceToApiPath(context.index().getReference());
        String groupInstancePath = referenceToApiInstancePath(context.index().getReference());
        List<FieldListQuestionTarget> targets = new ArrayList<>();
        collectFieldListQuestionTargets(
                context.group().getChildren(),
                groupPath,
                groupInstancePath,
                targets);
        return Collections.unmodifiableList(targets);
    }

    private void collectFieldListQuestionTargets(
            List<?> children,
            String groupPath,
            String groupInstancePath,
            List<FieldListQuestionTarget> targets) {
        if (children == null) {
            return;
        }

        for (Object child : children) {
            if (child instanceof QuestionDef question) {
                if (question.getBind() == null || !(question.getBind().getReference() instanceof TreeReference reference)) {
                    continue;
                }
                String questionPath = referenceToApiPath(reference);
                String instancePath = fieldListInstancePath(groupPath, groupInstancePath, questionPath);
                targets.add(new FieldListQuestionTarget(questionPath, instancePath));
            } else if (child instanceof GroupDef nestedGroup) {
                if (nestedGroup.getRepeat()) {
                    // A repeated section has its own multiplicities and controls;
                    // do not flatten it into its parent's field-list page.
                    continue;
                }
                collectFieldListQuestionTargets(
                        nestedGroup.getChildren(),
                        groupPath,
                        groupInstancePath,
                        targets);
            }
        }
    }

    private String fieldListInstancePath(
            String groupPath,
            String groupInstancePath,
            String questionPath) {
        if (questionPath == null || questionPath.isBlank()) {
            return questionPath;
        }
        if (groupPath == null || groupPath.isBlank()
                || groupInstancePath == null || groupInstancePath.isBlank()) {
            return questionPath;
        }
        if (questionPath.equals(groupPath)) {
            return groupInstancePath;
        }
        if (questionPath.startsWith(groupPath + "/")) {
            // TreeReference#toString(true), which is the canonical instance path
            // used elsewhere in this runtime, includes a [1] multiplicity on
            // every non-repeat descendant as well as repeat multiplicities. A
            // bind path does not. Recreate those non-repeat multiplicities so a
            // target assembled from the form definition can be resolved against
            // a live JavaRosa instance.
            String suffix = questionPath.substring(groupPath.length() + 1);
            StringBuilder instanceSuffix = new StringBuilder();
            for (String segment : suffix.split("/")) {
                if (segment.isBlank()) {
                    continue;
                }
                instanceSuffix.append('/').append(segment).append("[1]");
            }
            return groupInstancePath + instanceSuffix;
        }
        return questionPath;
    }

    /**
     * Whether the current JavaRosa event is the group itself or a descendant of
     * the supplied instance-specific group path.
     */
    public boolean isCurrentEventWithinGroup(String groupInstancePath) {
        if (groupInstancePath == null || groupInstancePath.isBlank()) {
            return false;
        }
        FormIndex index = model.getFormIndex();
        if (index == null || !index.isInForm() || index.getReference() == null) {
            return false;
        }
        String current = referenceToApiInstancePath(index.getReference());
        return groupInstancePath.equals(current)
                || (current != null && current.startsWith(groupInstancePath + "/"));
    }
    
    /**
     * Check whether the current position is on or inside a repeat.
     */
    public boolean isCurrentRepeat() {
        return getCurrentOrEnclosingRepeatContext() != null;
    }
    
    /**
     * Get the number of materialized repetitions for the current/enclosing
     * repeat.
     *
     * This is intentionally different from repeat_count. JavaRosa may
     * materialize count-controlled repetitions lazily as navigation reaches
     * them, while repeat_count describes the total number requested by the
     * XForm.
     */
    public int getCurrentRepeatCount() {
        RepeatContext context = getCurrentOrEnclosingRepeatContext();
        return context != null
                ? formDef.getNumRepetitions(context.index())
                : 0;
    }

    /**
     * Return true when the current/enclosing repeat is controlled by
     * JavaRosa's jr:count reference (the XForm representation generated from
     * XLSForm repeat_count).
     */
    public boolean isCurrentRepeatCountControlled() {
        RepeatContext context = getCurrentOrEnclosingRepeatContext();
        return context != null && context.group().getCountReference() != null;
    }

    /**
     * Return the evaluated repeat_count for the current/enclosing repeat.
     *
     * JavaRosa stores jr:count as a data reference on GroupDef. The reference
     * must be contextualized against the current repeat index, especially for
     * nested repeats, exactly as JavaRosa does in FormEntryModel.
     *
     * @return evaluated target count, or null when the repeat has no
     *         repeat_count or the count node currently has no value
     */
    public Integer getCurrentRepeatTargetCount() {
        RepeatContext context = getCurrentOrEnclosingRepeatContext();
        if (context == null || context.group().getCountReference() == null) {
            return null;
        }

        TreeReference countRef =
                FormInstance.unpackReference(context.group().getCountReference());
        TreeReference contextualized =
                countRef.contextualize(context.index().getReference());

        TreeElement countNode =
                formDef.getMainInstance().resolveReference(contextualized);
        if (countNode == null || countNode.getValue() == null) {
            return null;
        }

        long value = AnswerDataUtil.answerDataToInt(countNode.getValue());
        if (value <= 0) {
            return 0;
        }
        if (value > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "repeat_count exceeds supported web runtime range: " + value);
        }

        return (int) value;
    }

    /**
     * Return the zero-based multiplicity of the current/enclosing repeat
     * instance. Returns null when positioned on a non-instance repeat prompt.
     */
    public Integer getCurrentRepeatIndex() {
        RepeatContext context = getCurrentOrEnclosingRepeatContext();
        if (context == null) {
            return null;
        }

        int event = model.getEvent();
        if (event == FormEntryController.EVENT_PROMPT_NEW_REPEAT
                || event == FormEntryController.EVENT_REPEAT_JUNCTURE) {
            return null;
        }

        int multiplicity = context.index().getElementMultiplicity();
        return multiplicity >= 0 ? multiplicity : null;
    }

    /**
     * Whether the current repeat can be manually extended by the user.
     *
     * JavaRosa sets GroupDef.noAddRemove=true when jr:count is present, so
     * count-controlled repeats are always false here.
     */
    public boolean canAddCurrentRepeat() {
        RepeatContext context = getCurrentOrEnclosingRepeatContext();
        return context != null && !context.group().noAddRemove;
    }

    /**
     * Whether the current repeat instance can be manually deleted.
     *
     * A count-controlled/noAddRemove repeat cannot be deleted. A manual repeat
     * can only be deleted when the current position belongs to a materialized
     * repetition rather than the add-new-repeat prompt/juncture.
     */
    public boolean canDeleteCurrentRepeat() {
        RepeatContext context = getCurrentOrEnclosingRepeatContext();
        if (context == null || context.group().noAddRemove) {
            return false;
        }

        int event = model.getEvent();
        return event != FormEntryController.EVENT_PROMPT_NEW_REPEAT
                && event != FormEntryController.EVENT_REPEAT_JUNCTURE;
    }
    
    /**
     * Descend into an existing repeat instance.
     *
     * Position directly on the current/enclosing repeat before delegating to
     * JavaRosa. This works for both manual and repeat_count-controlled repeats
     * and avoids searching for an add-new-repeat prompt that intentionally does
     * not exist for fixed-count repeats.
     */
    public FormIndex descendIntoRepeat(int repeatIndex) {
        if (repeatIndex < 0) {
            throw new IllegalArgumentException("Repeat index must be zero or greater");
        }

        RepeatContext context = requireCurrentRepeatContext();

        int currentCount = formDef.getNumRepetitions(context.index());
        if (repeatIndex >= currentCount) {
            throw new IllegalArgumentException(
                    "Repeat index " + repeatIndex
                            + " is outside the current repeat count " + currentCount);
        }

        controller.jumpToIndex(context.index());
        return controller.descendIntoRepeat(repeatIndex);
    }

    /**
     * Create a new repeat instance for a user-managed repeat.
     *
     * repeat_count/jr:count repeats are created automatically by JavaRosa as
     * navigation reaches each configured repetition. Manual creation is
     * therefore rejected rather than allowing the client to exceed the XForm
     * count.
     */
    public void createNewRepeat() {
        RepeatContext context = requireCurrentRepeatContext();
        requireManualRepeatModification(context, "add");

        moveToCurrentRepeatPrompt(context);

        if (model.getEvent() != FormEntryController.EVENT_PROMPT_NEW_REPEAT) {
            throw new IllegalArgumentException(
                    "Current form position does not allow creating a new repeat");
        }

        controller.newRepeat();
    }

    /**
     * Delete the innermost user-managed repeat containing the current position.
     *
     * Deleting repeat_count/jr:count repetitions would violate the XForm's
     * configured cardinality, so it is rejected.
     */
    public void deleteRepeat() {
        RepeatContext context = requireCurrentRepeatContext();
        requireManualRepeatModification(context, "delete");

        if (!canDeleteCurrentRepeat()) {
            throw new IllegalArgumentException(
                    "Current form position does not reference an existing repeat instance");
        }

        FormIndex indexAfterDelete = controller.deleteRepeat();
        if (indexAfterDelete != null) {
            controller.jumpToIndex(indexAfterDelete);
        }
    }

    /**
     * Move to the add-new-repeat prompt for a user-managed repeat.
     */
    private void moveToCurrentRepeatPrompt(RepeatContext context) {
        if (model.getEvent() == FormEntryController.EVENT_PROMPT_NEW_REPEAT) {
            return;
        }

        controller.jumpToIndex(context.index());
        controller.jumpToNewRepeatPrompt();

        if (model.getEvent() != FormEntryController.EVENT_PROMPT_NEW_REPEAT) {
            throw new IllegalArgumentException(
                    "Current form position is not inside a user-managed repeat");
        }
    }

    /**
     * Jump to the add-new-repeat prompt for the current user-managed repeat.
     */
    public void jumpToNewRepeatPrompt() {
        RepeatContext context = requireCurrentRepeatContext();
        requireManualRepeatModification(context, "add");
        moveToCurrentRepeatPrompt(context);
    }

    /**
     * Create a new user-managed repeat instance.
     */
    public void newRepeat() {
        createNewRepeat();
    }

    /**
     * Create a new user-managed repeat instance at a specific FormIndex.
     */
    public void newRepeat(FormIndex formIndex) {
        if (formIndex == null) {
            throw new IllegalArgumentException("Repeat FormIndex must not be null");
        }

        RepeatContext context = repeatContextForIndex(formIndex);
        if (context == null) {
            throw new IllegalArgumentException(
                    "FormIndex does not reference a repeat");
        }

        requireManualRepeatModification(context, "add");
        controller.jumpToIndex(formIndex);

        if (model.getEvent() != FormEntryController.EVENT_PROMPT_NEW_REPEAT) {
            throw new IllegalArgumentException(
                    "FormIndex does not reference a new-repeat prompt");
        }

        controller.newRepeat(formIndex);
    }

    /**
     * Find the innermost repeat represented by or containing the current
     * FormIndex. This also gives repeat_count support a nested-repeat-safe
     * context for evaluating the count reference.
     */
    private RepeatContext getCurrentOrEnclosingRepeatContext() {
        FormIndex currentIndex = model.getFormIndex();
        RepeatContext direct = repeatContextForIndex(currentIndex);
        if (direct != null) {
            return direct;
        }

        if (currentIndex == null || !currentIndex.isInForm()) {
            return null;
        }

        RepeatContext innermost = null;
        FormEntryCaption[] hierarchy = model.getCaptionHierarchy(currentIndex);
        if (hierarchy != null) {
            for (FormEntryCaption caption : hierarchy) {
                RepeatContext candidate = repeatContextForIndex(caption.getIndex());
                if (candidate != null) {
                    innermost = candidate;
                }
            }
        }

        return innermost;
    }

    private RepeatContext repeatContextForIndex(FormIndex index) {
        if (index == null || !index.isInForm()) {
            return null;
        }

        IFormElement element = formDef.getChild(index);
        if (element instanceof GroupDef group && group.getRepeat()) {
            return new RepeatContext(index, group);
        }

        return null;
    }

    private RepeatContext requireCurrentRepeatContext() {
        RepeatContext context = getCurrentOrEnclosingRepeatContext();
        if (context == null) {
            throw new IllegalArgumentException(
                    "Current form position is not inside a repeat");
        }
        return context;
    }

    private void requireManualRepeatModification(
            RepeatContext context,
            String operation) {

        if (!context.group().noAddRemove) {
            return;
        }

        if (context.group().getCountReference() != null) {
            throw new IllegalArgumentException(
                    "Cannot " + operation
                            + " a repeat controlled by XForm repeat_count");
        }

        throw new IllegalArgumentException(
                "Cannot " + operation
                        + " this repeat because the XForm disables add/remove");
    }

    private record RepeatContext(FormIndex index, GroupDef group) {
    }


    /**
     * Validate the entire form.
     * Returns a ValidationResult with success status and failed prompt info.
     *
     * JavaRosa 4.3.2 returns null when the validation sweep reaches the end of
     * the form without finding an invalid answer. A non-null ValidateOutcome
     * represents the first validation failure.
     */
    public ValidationResult validate() {
        ValidateOutcome outcome = formDef.validate();

        if (outcome == null) {
            return new ValidationResult(true, null, FormEntryController.ANSWER_OK);
        }

        return new ValidationResult(false, outcome.failedPrompt, outcome.outcome);
    }
    
    /**
     * Finalize the form entry and serialize to XML.
     */
    public String finalizeAndSerialize() {
        controller.finalizeFormEntry();
        
        try {
            byte[] xmlBytes = new XFormSerializingVisitor()
                .serializeInstance(formDef.getMainInstance());
            return new String(xmlBytes, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to serialize form instance", e);
        }
    }
    
    /**
     * Serialize instance without filtering by relevance (for drafts).
     */
    public String serializeDraft() {
        try {
            byte[] xmlBytes = new XFormSerializingVisitor(false)
                .serializeInstance(formDef.getMainInstance());
            return new String(xmlBytes, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to serialize draft instance", e);
        }
    }
    
    /**
     * Get the ODK/OpenRosa submission instance ID.
     *
     * Submission identity is stored in the primary instance's
     * <meta><instanceID> node. JavaRosa's DataInstance#getInstanceId() is a
     * separate internal field and is not automatically populated from that
     * node for a newly-created primary instance.
     */
    public String getInstanceId() {
        return ensureInstanceId();
    }

    /**
     * Set the ODK/OpenRosa submission instance ID.
     */
    public void setInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("Instance ID must not be blank");
        }

        TreeElement node = getInstanceIdNode();
        if (node == null) {
            throw new IllegalStateException(
                    "XForm does not contain a meta/instanceID node");
        }

        node.setAnswer(new StringData(instanceId.trim()));
    }

    /**
     * Preserve an existing meta/instanceID or generate one for a new form.
     *
     * JavaRosa's built-in uid preloader uses the same convention:
     * "uuid:" + PropertyUtils.genUUID().
     */
    private String ensureInstanceId() {
        TreeElement node = getInstanceIdNode();

        if (node != null) {
            IAnswerData answer = node.getValue();
            if (answer != null) {
                String existing = answer.getDisplayText();
                if (existing != null && !existing.isBlank()) {
                    return existing;
                }
            }

            String generated = "uuid:" + PropertyUtils.genUUID();
            node.setAnswer(new StringData(generated));
            return generated;
        }

        // Forms without the standard ODK meta node can still expose a
        // JavaRosa internal instance ID if one was supplied during parsing.
        return formDef.getMainInstance().getInstanceId();
    }

    private TreeElement getInstanceIdNode() {
        if (formDef == null || formDef.getMainInstance() == null) {
            return null;
        }

        TreeElement root = formDef.getMainInstance().getRoot();
        TreeElement meta = root.getChild("meta", 0);

        return meta != null
                ? meta.getChild("instanceID", 0)
                : null;
    }

    /**
     * Set the locale for the form.
     */
    public void setLocale(Locale locale) {
        formDef.getLocalizer().setLocale(locale.toString());
    }
    
    /**
     * Get available locales.
     */
    public String[] getAvailableLocales() {
        return formDef.getLocalizer().getAvailableLocales();
    }
    
    /**
     * Get the current locale.
     */
    public Locale getLocale() {
        String localeStr = formDef.getLocalizer().getLocale();
        return localeStr != null ? Locale.forLanguageTag(localeStr) : Locale.getDefault();
    }
    
    /**
     * Evaluate an XPath expression in the current context.
     */
    public Object evaluateXPath(String xpath) {
        try {
            XPathExpression expression = XPathParseTool.parseXPath(xpath);

            // Reuse FormDef's fully-configured evaluation context rather than
            // constructing an isolated one. The FormDef context contains secondary
            // instances and JavaRosa/XForms function handlers (for example
            // jr:choice-name), so expressions evaluated here behave the same way as
            // expressions evaluated by JavaRosa during normal form processing.
            EvaluationContext baseContext = formDef.getEvaluationContext();
            EvaluationContext context = baseContext;

            // When evaluation is requested while positioned on a question, scope the
            // derived context to that exact instance reference. This is especially
            // important inside repeats: setting only originalContext leaves the XPath
            // context node at the document root and gives position/index-sensitive
            // expressions the wrong repeat context.
            if (model.getEvent() == FormEntryController.EVENT_QUESTION) {
                FormEntryPrompt prompt = model.getQuestionPrompt();
                if (prompt != null && prompt.getIndex() != null && prompt.getIndex().getReference() != null) {
                    TreeReference currentReference = prompt.getIndex().getReference();
                    context = new EvaluationContext(baseContext, currentReference);
                    context.setOriginalContext(currentReference);
                }
            }

            Object result = expression.eval(formDef.getMainInstance(), context);

            // JavaRosa path expressions return XPathNodeset objects. The public
            // FormDefinition API exposes evaluated values rather than JavaRosa's
            // internal nodeset container, so use JavaRosa's own scalar conversion.
            if (result instanceof XPathNodeset nodeset) {
                return nodeset.unpack();
            }

            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to evaluate XPath: " + xpath, e);
        }
    }
    
    /**
     * Check if a node is relevant by path.
     */
    public boolean isRelevant(String xpath) {
        try {
            Object result = evaluateXPath(xpath);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            return true; // Default to relevant if not a boolean
        } catch (Exception e) {
            return true; // Default to relevant on error
        }
    }
    
    /**
     * Get a TreeElement by XPath reference.
     */
    public TreeElement getNode(String xpath) {
        try {
            XPathReference reference = new XPathReference(xpath);
            TreeReference treeRef = FormInstance.unpackReference(reference);
            return formDef.getMainInstance().resolveReference(treeRef);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Set a value directly on a node (for calculations, etc.).
     */
    public void setValue(String xpath, IAnswerData value) {
        TreeElement node = getNode(xpath);
        if (node != null) {
            node.setAnswer(value);
        }
    }
    
    /**
     * Get the form title.
     */
    public String getFormTitle() {
        return formDef.getName();
    }
    
    /**
     * Get the XML form ID (from the instance data id attribute).
     */
    public String getXmlFormId() {
        String instanceId = formDef.getMainInstance().getInstanceId();
        // If instanceId is a UUID, return the form ID from the root element's id attribute
        if (instanceId != null && instanceId.startsWith("uuid:")) {
            TreeElement root = formDef.getMainInstance().getRoot();
            return root != null ? getRootElementId(root) : "unknown_form";
        }
        // If instanceId is null or empty, fall back to the root element's id attribute
        if (instanceId == null || instanceId.isEmpty()) {
            TreeElement root = formDef.getMainInstance().getRoot();
            return root != null ? getRootElementId(root) : "unknown_form";
        }
        return instanceId;
    }
    
    /**
     * Get the id attribute from the root element of the instance.
     */
    private String getRootElementId(TreeElement root) {
        // The root element's id attribute is stored as a child element with name "id" in some cases,
        // or we can get it from the element's attributes. In JavaRosa, the id attribute
        // from the instance XML is typically available via getAttributeValue.
        // Try to get the "id" attribute from the root element
        String idAttr = root.getAttributeValue(null, "id");
        if (idAttr != null && !idAttr.isEmpty()) {
            return idAttr;
        }
        // Fallback to element name
        return root.getName();
    }
    
    /**
     * Get a question definition by path.
     */
    public QuestionDef getQuestion(String questionPath, String repeatPath, Integer repeatIndex) {
        try {
            String xpath = buildXPath(questionPath, repeatPath, repeatIndex);
            TreeElement node = getNode(xpath);
            if (node != null) {
                // Get the QuestionDef from the formDef using the bind reference
                TreeReference ref = node.getRef();
                if (ref != null) {
                    return findQuestionByRef(formDef, ref);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get question at path: {}", questionPath, e);
        }
        return null;
    }
    
    /**
     * Find a QuestionDef by TreeReference by traversing the form definition tree.
     * The bind reference in QuestionDef is a relative reference from the form root.
     */
    QuestionDef findQuestionByRef(FormDef formDef, TreeReference ref) {
        // Traverse the form definition to find the question with matching reference
        return findQuestionInChildren(formDef.getChildren(), ref);
    }
    
    QuestionDef findQuestionInChildren(List<?> children, TreeReference ref) {
        if (children == null) return null;
        for (Object child : children) {
            if (child instanceof QuestionDef) {
                QuestionDef q = (QuestionDef) child;
                if (q.getBind() != null) {
                    // getReference() returns Object, need to cast
                    Object bindRefObj = q.getBind().getReference();
                    if (bindRefObj instanceof TreeReference) {
                        TreeReference bindRef = (TreeReference) bindRefObj;
                        // Compare references by their string representation or by name path
                        if (referencesEqual(bindRef, ref)) {
                            return q;
                        }
                    }
                }
            } else if (child instanceof GroupDef) {
                GroupDef g = (GroupDef) child;
                QuestionDef found = findQuestionInChildren(g.getChildren(), ref);
                if (found != null) return found;
            }
        }
        return null;
    }
    
    /**
     * Compare two TreeReferences for equality by comparing their name paths.
     */
    private boolean referencesEqual(TreeReference ref1, TreeReference ref2) {
        if (ref1 == null || ref2 == null) return false;
        if (ref1.size() != ref2.size()) return false;
        for (int i = 0; i < ref1.size(); i++) {
            if (!ref1.getName(i).equals(ref2.getName(i))) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Get all calculation questions in the form.
     *
     * JavaRosa parses calculate expressions into its private Triggerable DAG and
     * does not expose a public API to enumerate those triggerables. For metadata
     * discovery we use the original XForm bind metadata cached at construction
     * time. JavaRosa remains responsible for calculation evaluation and DAG order.
     */
    public List<QuestionDef> getCalculations() {
        List<QuestionDef> calculations = new ArrayList<>();
        collectCalculationsFromFormDef(formDef.getChildren(), calculations);
        return calculations;
    }

    private void collectCalculationsFromFormDef(List<?> children, List<QuestionDef> calculations) {
        if (children == null) {
            return;
        }

        for (Object child : children) {
            if (child instanceof QuestionDef q) {
                BindMetadata metadata = getBindMetadata(q);
                if (metadata != null && metadata.calculate != null) {
                    calculations.add(q);
                }
            } else if (child instanceof GroupDef g) {
                collectCalculationsFromFormDef(g.getChildren(), calculations);
            }
        }
    }

    /**
     * Build XPath for a question with repeat context.
     */
    private String buildXPath(String questionPath, String repeatPath, Integer repeatIndex) {
        if (questionPath == null || questionPath.isBlank()) {
            throw new IllegalArgumentException("Question path must not be blank");
        }

        String rootName = formDef.getMainInstance().getRoot().getName();

        String normalizedQuestion = questionPath.trim();
        if (normalizedQuestion.startsWith("/")) {
            normalizedQuestion = normalizedQuestion.substring(1);
        }

        String normalizedRepeat = repeatPath == null ? "" : repeatPath.trim();
        if (normalizedRepeat.startsWith("/")) {
            normalizedRepeat = normalizedRepeat.substring(1);
        }

        // Public web-form paths are relative to the main instance root. Avoid
        // duplicating the root when the caller already supplied it.
        String rootPrefix = rootName + "/";
        if (normalizedQuestion.startsWith(rootPrefix)) {
            normalizedQuestion = normalizedQuestion.substring(rootPrefix.length());
        }
        if (normalizedRepeat.startsWith(rootPrefix)) {
            normalizedRepeat = normalizedRepeat.substring(rootPrefix.length());
        }

        StringBuilder xpath = new StringBuilder("/").append(rootName);

        if (!normalizedRepeat.isEmpty()) {
            xpath.append("/").append(normalizedRepeat);
            if (repeatIndex != null) {
                xpath.append("[").append(repeatIndex + 1).append("]");
            }

            // A caller may supply the complete question path together with its
            // repeat path. Keep only the portion below the repeat in that case.
            String repeatPrefix = normalizedRepeat + "/";
            if (normalizedQuestion.startsWith(repeatPrefix)) {
                normalizedQuestion = normalizedQuestion.substring(repeatPrefix.length());
            }
        }

        if (!normalizedQuestion.isEmpty()) {
            xpath.append("/").append(normalizedQuestion);
        }

        return xpath.toString();
    }
    
    /**
     * Get all questions that have a relevant bind expression.
     */
    public List<QuestionDef> getRelevantQuestions() {
        List<QuestionDef> relevantQuestions = new ArrayList<>();
        collectRelevantQuestionsFromFormDef(formDef.getChildren(), relevantQuestions);
        return relevantQuestions;
    }

    private void collectRelevantQuestionsFromFormDef(List<?> children, List<QuestionDef> relevantQuestions) {
        if (children == null) {
            return;
        }

        for (Object child : children) {
            if (child instanceof QuestionDef q) {
                BindMetadata metadata = getBindMetadata(q);
                if (metadata != null && metadata.relevant != null) {
                    relevantQuestions.add(q);
                }
            } else if (child instanceof GroupDef g) {
                collectRelevantQuestionsFromFormDef(g.getChildren(), relevantQuestions);
            }
        }
    }

    /**
     * Get the original relevant XPath expression for a question's TreeElement.
     */
    public String getRelevantExpression(TreeElement element) {
        BindMetadata metadata = getBindMetadata(element);
        return metadata != null ? metadata.relevant : null;
    }

    /**
     * Get the original calculate XPath expression for a question's TreeElement.
     */
    public String getCalculateExpression(TreeElement element) {
        BindMetadata metadata = getBindMetadata(element);
        return metadata != null ? metadata.calculate : null;
    }

    /**
     * Get the constraint XPath expression for a question's TreeElement.
     */
    public String getConstraintExpression(TreeElement element) {
        return getConstraintXPath(element);
    }

    /**
     * Extract the original XPath from JavaRosa's parsed Constraint object.
     */
    private String getConstraintXPath(TreeElement element) {
        if (element == null) {
            return null;
        }

        Constraint constraint = element.getConstraint();
        if (constraint == null || constraint.constraint == null) {
            return null;
        }

        if (constraint.constraint instanceof XPathConditional xpathConditional) {
            return xpathConditional.xpath;
        }

        return constraint.constraint.toString();
    }
    
    /**
     * Get the constraint message for a question's TreeElement.
     */
    public String getConstraintMessage(TreeElement element) {
        return element.getBindAttributeValue(null, "constraintMsg");
    }
    
    private BindMetadata getBindMetadata(QuestionDef questionDef) {
        if (questionDef == null || questionDef.getBind() == null) {
            return null;
        }

        Object reference = questionDef.getBind().getReference();
        if (!(reference instanceof TreeReference treeReference)) {
            return null;
        }

        return bindMetadataByPath.get(normalizeBindPath(referenceToPath(treeReference)));
    }

    private BindMetadata getBindMetadata(TreeElement element) {
        if (element == null || element.getRef() == null) {
            return null;
        }

        return bindMetadataByPath.get(normalizeBindPath(referenceToPath(element.getRef())));
    }

    private static Map<String, BindMetadata> parseBindMetadata(String xformXml) {
        if (xformXml == null || xformXml.isBlank()) {
            return Collections.emptyMap();
        }

        Map<String, BindMetadata> metadataByPath = new HashMap<>();
        Matcher bindMatcher = BIND_TAG_PATTERN.matcher(xformXml);

        while (bindMatcher.find()) {
            String bindTag = bindMatcher.group();
            Map<String, String> attributes = parseQuotedAttributes(bindTag);

            String nodeset = firstNonBlank(
                    attributes.get("nodeset"),
                    attributes.get("ref")
            );

            if (nodeset == null) {
                continue;
            }

            metadataByPath.put(
                    normalizeBindPath(nodeset),
                    new BindMetadata(
                            blankToNull(attributes.get("relevant")),
                            blankToNull(attributes.get("calculate"))
                    )
            );
        }

        return Collections.unmodifiableMap(metadataByPath);
    }

    private static Map<String, String> parseUploadMediaTypes(String xformXml) {
        if (xformXml == null || xformXml.isBlank()) {
            return Collections.emptyMap();
        }

        Map<String, String> mediaTypes = new HashMap<>();
        Matcher uploadMatcher = UPLOAD_TAG_PATTERN.matcher(xformXml);

        while (uploadMatcher.find()) {
            Map<String, String> attributes = parseQuotedAttributes(uploadMatcher.group());
            String reference = firstNonBlank(attributes.get("ref"), attributes.get("nodeset"));
            String mediaType = firstNonBlank(attributes.get("mediatype"), attributes.get("mediaType"));

            if (reference != null && mediaType != null) {
                mediaTypes.put(normalizeBindPath(reference), mediaType);
            }
        }

        return Collections.unmodifiableMap(mediaTypes);
    }

    private static Map<String, String> parseUploadAppearances(String xformXml) {
        if (xformXml == null || xformXml.isBlank()) {
            return Collections.emptyMap();
        }

        Map<String, String> appearances = new HashMap<>();
        Matcher uploadMatcher = UPLOAD_TAG_PATTERN.matcher(xformXml);

        while (uploadMatcher.find()) {
            Map<String, String> attributes = parseQuotedAttributes(uploadMatcher.group());
            String reference = firstNonBlank(attributes.get("ref"), attributes.get("nodeset"));
            String appearance = blankToNull(attributes.get("appearance"));

            if (reference != null && appearance != null) {
                appearances.put(normalizeBindPath(reference), appearance);
            }
        }

        return Collections.unmodifiableMap(appearances);
    }

    private static Map<String, String> parseQuotedAttributes(String tag) {
        Map<String, String> attributes = new HashMap<>();
        Matcher matcher = ATTRIBUTE_PATTERN.matcher(tag);

        while (matcher.find()) {
            String rawName = matcher.group(1);
            String value = matcher.group(3);

            // Standard XForms attributes are unqualified, but normalizing a
            // prefix away makes the metadata reader tolerant of prefixed forms.
            int colon = rawName.indexOf(':');
            String localName = colon >= 0
                    ? rawName.substring(colon + 1)
                    : rawName;

            attributes.put(localName, decodeBasicXmlEntities(value));
        }

        return attributes;
    }

    /**
     * Decode only the five predefined XML entities. This scanner never expands
     * custom/general entities and never reads a DTD or external resource.
     */
    private static String decodeBasicXmlEntities(String value) {
        if (value == null || value.indexOf('&') < 0) {
            return value;
        }

        return value
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static String normalizeBindPath(String path) {
        if (path == null) {
            return "";
        }

        String normalized = path.trim().replace('\\', '/');

        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    private static String firstNonBlank(String first, String second) {
        String value = blankToNull(first);
        return value != null ? value : blankToNull(second);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class BindMetadata {
        private final String relevant;
        private final String calculate;

        private BindMetadata(String relevant, String calculate) {
            this.relevant = relevant;
            this.calculate = calculate;
        }
    }

    /**
     * Get the question path (name) from a QuestionDef.
     */
    public String getQuestionPath(QuestionDef qDef) {
        if (qDef.getBind() != null) {
            // Resolve the bind reference to get the TreeElement, then get its name
            TreeElement element = formDef.getMainInstance().resolveReference(qDef.getBind());
            if (element != null) {
                return element.getName();
            }
        }
        return "";
    }
    
    /**
     * Get the repeat path from a QuestionDef.
     * This is the path to the parent repeat group.
     */
    public String getRepeatPath(QuestionDef qDef) {
        if (qDef.getBind() != null) {
            TreeElement element = formDef.getMainInstance().resolveReference(qDef.getBind());
            if (element != null) {
                // Build path without the last element (the question itself)
                TreeReference ref = element.getRef();
                if (ref != null) {
                    StringBuilder path = new StringBuilder();
                    for (int i = 0; i < ref.size() - 1; i++) {
                        if (i > 0) path.append("/");
                        path.append(ref.getName(i));
                    }
                    return path.toString();
                }
            }
        }
        return "";
    }
    
    /**
     * Get the repeat index from a QuestionDef.
     * This is runtime-specific and not stored in QuestionDef.
     * Returns 0 as default.
     */
    public Integer getRepeatIndex(QuestionDef qDef) {
        // Repeat index is runtime-specific, not stored in QuestionDef
        return 0;
    }
    
    /**
     * Get question type string from QuestionDef.
     */
    public String getQuestionTypeString(QuestionDef qDef) {
        // Use control type since IDataReference doesn't have getDataType()
        int controlType = qDef.getControlType();
        switch (controlType) {
            case 1: return "text"; // CONTROL_INPUT
            case 2: return "select1"; // CONTROL_SELECT_ONE
            case 3: return "select"; // CONTROL_SELECT_MULTI
            case 4: return "trigger"; // CONTROL_TRIGGER
            case 5: return "binary"; // CONTROL_UPLOAD
            case 6: return "group"; // CONTROL_GROUP
            case 7: return "repeat"; // CONTROL_REPEAT
            case 8: return "note"; // CONTROL_LABEL
            case 9: return "hidden"; // CONTROL_HIDDEN
            default: return "string";
        }
    }
    
    /**
     * Get the TreeElement for a question by its QuestionDef.
     */
    public TreeElement getTreeElement(QuestionDef qDef) {
        if (qDef.getBind() != null) {
            return formDef.getMainInstance().resolveReference(qDef.getBind());
        }
        return null;
    }
    
    // Inner classes for DTOs
    
    public static class SelectChoice {
        private final String value;
        private final String label;
        
        public SelectChoice(String value, String label) {
            this.value = value;
            this.label = label;
        }
        
        public String getValue() { return value; }
        public String getLabel() { return label; }
    }
    
    public record GroupContext(
            String label,
            String path,
            String instancePath,
            boolean repeat,
            Integer repeatIndex,
            int depth,
            String appearanceHint) {
    }

    private record FieldListContext(FormIndex index, GroupDef group) {}

    public record FieldListQuestionTarget(String path, String instancePath) {}

    public static class ValidationResult {
        private final boolean valid;
        private final FormIndex failedPrompt;
        private final int outcomeCode;
        
        public ValidationResult(boolean valid, FormIndex failedPrompt, int outcomeCode) {
            this.valid = valid;
            this.failedPrompt = failedPrompt;
            this.outcomeCode = outcomeCode;
        }
        
        public boolean isValid() { return valid; }
        public FormIndex getFailedPrompt() { return failedPrompt; }
        public int getOutcomeCode() { return outcomeCode; }
    }
}