package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javarosa.core.model.Constants;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.instance.FormInstance;
import org.javarosa.core.model.GroupDef;
import org.javarosa.core.model.IFormElement;
import org.javarosa.core.model.QuestionDef;
import org.javarosa.core.model.SelectChoice;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.services.locale.Localizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class XFormBodyExtractor {

    private final JavaRosaFormLoader formLoader;
    private final XFormITextExtractor itextExtractor;

    public BodyExtractionResult extractBody(String xmlContent) {
        return extractBody(formLoader.loadFormDef(xmlContent));
    }

    /** Extracts the XForm body from JavaRosa's IFormElement definition tree. */
    public BodyExtractionResult extractBody(FormDef formDef) {
        List<ControlInfo> controls = new ArrayList<>();
        Map<String, ControlInfo> controlsByRef = new HashMap<>();

        Map<String, Map<String, String>> bindIndex = formDef == null
                ? Map.of()
                : formLoader.extractBindAttributes(formDef);
        
        // Extract media types from upload elements in raw XML
        Map<String, String> mediaTypes = formDef == null
                ? Map.of()
                : formLoader.extractMediaTypes(formDef);
        
        // Extract IText translations from raw XML (JavaRosa doesn't parse <itext>)
        Map<String, String> translations = formDef == null
                ? Map.of()
                : getTranslationsMap(formDef);

        if (formDef != null) {
            traverseBody(formDef, formDef.getChildren(), controls, controlsByRef, 0, "", bindIndex, mediaTypes, translations);
        }

        String formId = formDef == null ? "" : formDef.getName();
        log.debug("Extracted {} controls from form: {}", controls.size(), formId);
        return BodyExtractionResult.builder()
                .formId(formId)
                .controls(controls)
                .controlsByRef(controlsByRef)
                .build();
    }
    
    private Map<String, String> getTranslationsMap(FormDef formDef) {
        log.debug("getTranslationsMap called for formDef: {}", formDef != null ? formDef.getName() : "null");
        XFormITextExtractor.ITextExtractionResult result = itextExtractor.extractIText(formDef);
        log.debug("ITextExtractionResult: {}", result);
        Map<String, String> translations = new HashMap<>();
        if (result != null) {
            String defaultLanguage = result.getDefaultLanguage();
            log.debug("Default language: {}", defaultLanguage);
            log.debug("Texts by language keys: {}", result.getTextsByLanguage().keySet());
            if (defaultLanguage != null) {
                List<XFormITextExtractor.ITextInfo> defaultLanguageTexts = result.getTextsByLanguage().get(defaultLanguage);
                log.debug("Default language texts count: {}", defaultLanguageTexts != null ? defaultLanguageTexts.size() : 0);
                if (defaultLanguageTexts != null) {
                    for (XFormITextExtractor.ITextInfo text : defaultLanguageTexts) {
                        log.debug("ITextInfo: id={}, hasLabel={}, label={}, hasHint={}, hint={}", 
                                text.getId(), text.hasLabel(), text.getLabel(), text.hasHint(), text.getHint());
                        if (text.hasLabel()) {
                            log.debug("Adding translation: id={}, label={}", text.getId(), text.getLabel());
                            translations.put(text.getId(), text.getLabel());
                        }
                    }
                }
            }
        }
        log.debug("Total translations: {}", translations.size());
        log.debug("Translations map: {}", translations);
        return translations;
    }

    private void traverseBody(FormDef formDef,
                              List<IFormElement> elements,
                              List<ControlInfo> controls,
                              Map<String, ControlInfo> controlsByRef,
                              int depth,
                              String parentPath,
                              Map<String, Map<String, String>> bindIndex,
                              Map<String, String> mediaTypes,
                              Map<String, String> translations) {
        if (elements == null) {
            return;
        }
        for (IFormElement element : elements) {
            ControlInfo control = extractControlInfo(formDef, element, depth, parentPath, bindIndex, mediaTypes, translations);
            controls.add(control);
            if (!isBlank(control.getXpathReference())) {
                controlsByRef.put(control.getXpathReference(), control);
            }
            String nextParent = isBlank(control.getXpathReference())
                    ? appendPath(parentPath, control.getNodeName())
                    : control.getXpathReference();
            traverseBody(formDef, element.getChildren(), controls, controlsByRef, depth + 1, nextParent, bindIndex, mediaTypes, translations);
        }
    }

    private ControlInfo extractControlInfo(FormDef formDef,
                                           IFormElement element,
                                           int depth,
                                           String parentPath,
                                           Map<String, Map<String, String>> bindIndex,
                                           Map<String, String> mediaTypes,
                                           Map<String, String> translations) {
        String xpathReference = getXPathReference(element);
        String nodeName = getNodeName(element, xpathReference);
        TreeElement instanceNode = resolveInstanceNode(formDef, element);
        Map<String, String> bindAttributes = bindIndex.getOrDefault(normalizeReference(xpathReference), Map.of());
        String controlType = getControlType(formDef, element, instanceNode, bindAttributes);

        // Get media type from extracted media types map (for upload controls)
        String mediaType = mediaTypes.get(normalizeReference(xpathReference));
        
        // Get label from translations map (for itext references)
        String label = translations.get(xpathReference + ":label");
        if (label == null) {
            label = resolveLabel(formDef, element);
        }
        
        // Get hint from translations map
        String hint = translations.get(xpathReference + ":hint");
        log.debug("Looking up hint for xpathReference={}, key={}, found={}", xpathReference, xpathReference + ":hint", hint);
        if (hint == null && element instanceof QuestionDef) {
            hint = resolveHint(formDef, (QuestionDef) element);
        }

        ControlInfo.ControlInfoBuilder builder = ControlInfo.builder()
                .nodeName(nodeName)
                .xpathReference(xpathReference)
                .controlType(controlType)
                .depth(depth)
                .parentPath(parentPath)
                // Keep the old property name/getControl() while using the real 4.3.2 type.
                .control(element)
                .label(label)
                .hint(hint)
                .appearance(element.getAppearanceAttr())
                .mediaType(mediaType)
                .bind(extractBindInfo(instanceNode, bindIndex.getOrDefault(normalizeReference(xpathReference), Map.of())));

        if (element instanceof QuestionDef) {
            QuestionDef question = (QuestionDef) element;
            builder.choices(extractChoices(formDef, question));
            builder.inputMode(firstNonBlank(
                    question.getAdditionalAttribute(null, "inputmode"),
                    question.getAdditionalAttribute(null, "inputMode")));
            builder.mediaType(firstNonBlank(
                    question.getAdditionalAttribute(null, "mediatype"),
                    question.getAdditionalAttribute(null, "mediaType"),
                    mediaType));
            builder.rangeStart(question.getAdditionalAttribute(null, "start"));
            builder.rangeEnd(question.getAdditionalAttribute(null, "end"));
            builder.rangeStep(question.getAdditionalAttribute(null, "step"));
            if (question.getControlType() == Constants.CONTROL_SELECT_ONE) {
                builder.selectionMode("single");
            } else if (question.getControlType() == Constants.CONTROL_SELECT_MULTI
                    || question.getControlType() == Constants.CONTROL_RANK) {
                builder.selectionMode("multiple");
            }
        }

        List<IFormElement> childElements = element.getChildren();
        if (childElements != null && !childElements.isEmpty()) {
            List<ControlInfo> children = new ArrayList<>();
            Map<String, ControlInfo> childrenByRef = new HashMap<>();
            String childParentPath = isBlank(xpathReference)
                    ? appendPath(parentPath, nodeName)
                    : xpathReference;
            for (IFormElement child : childElements) {
                ControlInfo childInfo = extractControlInfo(formDef, child, depth + 1, childParentPath, bindIndex, mediaTypes, translations);
                children.add(childInfo);
                if (!isBlank(childInfo.getXpathReference())) {
                    childrenByRef.put(childInfo.getXpathReference(), childInfo);
                }
            }
            builder.children(children).childrenByRef(childrenByRef);
        }

        return builder.build();
    }

    private String getControlType(FormDef formDef, IFormElement element) {
        return getControlType(formDef, element, null, null);
    }

    /**
     * Determines the control type for a form element.
     * Handles the case where JavaRosa parses upload elements with specific media types
     * (e.g., image/*, audio/*, video/*) as capture controls (CONTROL_IMAGE_CHOOSE, etc.)
     * instead of CONTROL_UPLOAD. We detect this by checking if the data type is binary
     * and there's a media type, in which case it's an upload control.
     */
    private String getControlType(FormDef formDef, IFormElement element, TreeElement instanceNode, Map<String, String> bindAttributes) {
        if (element instanceof GroupDef) {
            return ((GroupDef) element).getRepeat() ? "repeat" : "group";
        }
        if (!(element instanceof QuestionDef)) {
            return "unknown";
        }

        QuestionDef question = (QuestionDef) element;
        int controlType = question.getControlType();
        
        // First, check if this is actually an upload control.
        // JavaRosa may parse <upload mediatype="image/*"> as CONTROL_IMAGE_CHOOSE,
        // <upload mediatype="audio/*"> as CONTROL_AUDIO_CAPTURE, etc.
        // But if the data type is binary and there's a media type, it's an upload.
        if (isUploadControl(controlType, instanceNode, bindAttributes, question)) {
            return "upload";
        }

        return switch (controlType) {
            case Constants.CONTROL_SELECT_ONE -> "select1";
            case Constants.CONTROL_SELECT_MULTI -> "select";
            case Constants.CONTROL_TEXTAREA -> "textarea";
            case Constants.CONTROL_SECRET -> "secret";
            case Constants.CONTROL_RANGE -> "range";
            case Constants.CONTROL_UPLOAD -> "upload";
            case Constants.CONTROL_SUBMIT -> "submit";
            case Constants.CONTROL_TRIGGER -> "trigger";
            case Constants.CONTROL_IMAGE_CHOOSE -> "image";
            case Constants.CONTROL_LABEL -> "note";
            case Constants.CONTROL_AUDIO_CAPTURE -> "audio";
            case Constants.CONTROL_VIDEO_CAPTURE -> "video";
            case Constants.CONTROL_OSM_CAPTURE -> "osm";
            case Constants.CONTROL_FILE_CAPTURE -> "file";
            case Constants.CONTROL_RANK -> "rank";
            case Constants.CONTROL_INPUT -> "input";
            default -> "input";
        };
    }

    private boolean isUploadControl(int controlType, TreeElement instanceNode, Map<String, String> bindAttributes, QuestionDef question) {
        // Check if data type is binary (from instance node or bind attributes)
        boolean isBinary = false;
        if (instanceNode != null) {
            isBinary = instanceNode.getDataType() == Constants.DATATYPE_BINARY;
        } else if (bindAttributes != null) {
            String type = bindAttributes.get("type");
            isBinary = "binary".equalsIgnoreCase(type);
        }
        
        // Check if there's a media type (from bind attributes or question attributes)
        boolean hasMediaType = false;
        if (bindAttributes != null) {
            String mediaType = bindAttributes.get("mediatype");
            if (mediaType == null) {
                mediaType = bindAttributes.get("mediaType");
            }
            hasMediaType = mediaType != null && !mediaType.isBlank();
        } else if (question != null) {
            String mediaType = question.getAdditionalAttribute(null, "mediatype");
            if (mediaType == null) {
                mediaType = question.getAdditionalAttribute(null, "mediaType");
            }
            hasMediaType = mediaType != null && !mediaType.isBlank();
        }
        
        // If binary data type with media type, and control type is a capture type,
        // it's actually an upload control
        if (isBinary && hasMediaType) {
            return controlType == Constants.CONTROL_IMAGE_CHOOSE
                    || controlType == Constants.CONTROL_AUDIO_CAPTURE
                    || controlType == Constants.CONTROL_VIDEO_CAPTURE
                    || controlType == Constants.CONTROL_FILE_CAPTURE
                    || controlType == Constants.CONTROL_UPLOAD;
        }
        return false;
    }

    private String getInputType(TreeElement node) {
        if (node == null) {
            return "input";
        }
        return switch (node.getDataType()) {
            case Constants.DATATYPE_TEXT -> "text";
            case Constants.DATATYPE_INTEGER, Constants.DATATYPE_LONG -> "integer";
            case Constants.DATATYPE_DECIMAL -> "decimal";
            case Constants.DATATYPE_DATE -> "date";
            case Constants.DATATYPE_TIME -> "time";
            case Constants.DATATYPE_DATE_TIME -> "dateTime";
            case Constants.DATATYPE_BOOLEAN -> "boolean";
            case Constants.DATATYPE_GEOPOINT -> "geopoint";
            case Constants.DATATYPE_GEOSHAPE -> "geoshape";
            case Constants.DATATYPE_GEOTRACE -> "geotrace";
            case Constants.DATATYPE_BARCODE -> "barcode";
            case Constants.DATATYPE_BINARY -> "binary";
            default -> "input";
        };
    }

    private BindInfo extractBindInfo(TreeElement node, Map<String, String> sourceAttributes) {
        if (node == null && (sourceAttributes == null || sourceAttributes.isEmpty())) {
            return null;
        }
        int dataType = node == null ? Constants.DATATYPE_NULL : node.getDataType();
        String mediaType = firstNonBlank(
                attribute(sourceAttributes, "mediatype", "mediaType"),
                node == null ? null : node.getBindAttributeValue(null, "mediatype"),
                node == null ? null : node.getBindAttributeValue(null, "mediaType"));
        return BindInfo.builder()
                .dataType(firstNonBlank(
                        attribute(sourceAttributes, "type"),
                        node == null ? null : node.getBindAttributeValue(null, "type"),
                        dataTypeName(dataType)))
                .constraint(firstNonBlank(
                        attribute(sourceAttributes, "constraint"),
                        node == null ? null : node.getBindAttributeValue(null, "constraint")))
                .constraintMessage(firstNonBlank(
                        attribute(sourceAttributes, "constraintMsg", "constraintmsg"),
                        node == null ? null : node.getBindAttributeValue(null, "constraintMsg"),
                        node == null ? null : node.getBindAttributeValue(null, "constraintmsg")))
                .relevant(firstNonBlank(
                        attribute(sourceAttributes, "relevant"),
                        node == null ? null : node.getBindAttributeValue(null, "relevant")))
                .calculate(firstNonBlank(
                        attribute(sourceAttributes, "calculate"),
                        node == null ? null : node.getBindAttributeValue(null, "calculate")))
                .required(firstNonBlank(
                        attribute(sourceAttributes, "required"),
                        node == null ? null : node.getBindAttributeValue(null, "required")))
                .readOnly(firstNonBlank(
                        attribute(sourceAttributes, "readonly", "readOnly"),
                        node == null ? null : node.getBindAttributeValue(null, "readonly"),
                        node == null ? null : node.getBindAttributeValue(null, "readOnly")))
                .attachment(dataType == Constants.DATATYPE_BINARY || !isBlank(mediaType))
                .attachmentMediaType(mediaType)
                .validation(firstNonBlank(
                        attribute(sourceAttributes, "validate"),
                        node == null ? null : node.getBindAttributeValue(null, "validate")))
                .validationMessage(firstNonBlank(
                        attribute(sourceAttributes, "validateMsg", "validatemsg"),
                        node == null ? null : node.getBindAttributeValue(null, "validateMsg"),
                        node == null ? null : node.getBindAttributeValue(null, "validatemsg")))
                .build();
    }

    private List<ChoiceInfo> extractChoices(FormDef formDef, QuestionDef question) {
        List<ChoiceInfo> choices = new ArrayList<>();
        if (question.getChoices() == null) {
            return choices;
        }
        for (SelectChoice choice : question.getChoices()) {
            choices.add(ChoiceInfo.builder()
                    .value(choice.getValue())
                    .label(resolveChoiceLabel(formDef, choice))
                    .build());
        }
        return choices;
    }

    private String resolveLabel(FormDef formDef, IFormElement element) {
        return firstNonBlank(localizedText(formDef, element.getTextID()), element.getLabelInnerText());
    }

    private String resolveHint(FormDef formDef, QuestionDef question) {
        return firstNonBlank(
                localizedText(formDef, question.getHelpTextID()),
                question.getHelpText(),
                question.getHelpInnerText());
    }

    private String resolveChoiceLabel(FormDef formDef, SelectChoice choice) {
        return firstNonBlank(localizedText(formDef, choice.getTextID()), choice.getLabelInnerText());
    }

    private String localizedText(FormDef formDef, String textId) {
        if (formDef == null || isBlank(textId)) {
            return null;
        }
        Localizer localizer = formDef.getLocalizer();
        if (localizer == null) {
            return null;
        }
        String locale = firstNonBlank(localizer.getLocale(), localizer.getDefaultLocale());
        if (isBlank(locale)) {
            return null;
        }
        try {
            return localizer.getText(textId, locale);
        } catch (RuntimeException e) {
            log.debug("Unable to resolve itext '{}' for locale '{}': {}", textId, locale, e.getMessage());
            return null;
        }
    }

    private TreeElement resolveInstanceNode(FormDef formDef, IFormElement element) {
        if (formDef == null || formDef.getMainInstance() == null || element == null || element.getBind() == null) {
            return null;
        }
        try {
            TreeReference reference = FormInstance.unpackReference(element.getBind());
            return formDef.getMainInstance().resolveReference(reference);
        } catch (RuntimeException e) {
            log.debug("Unable to resolve instance node for form element {}: {}", element.getID(), e.getMessage());
            return null;
        }
    }

    private String getXPathReference(IFormElement element) {
        if (element == null || element.getBind() == null) {
            return "";
        }
        try {
            return FormInstance.unpackReference(element.getBind()).toString();
        } catch (RuntimeException e) {
            return String.valueOf(element.getBind());
        }
    }

    private String getNodeName(IFormElement element, String reference) {
        if (element != null && element.getBind() != null) {
            try {
                return FormInstance.unpackReference(element.getBind()).getNameLast();
            } catch (RuntimeException ignored) {
                // Fall through to string parsing.
            }
        }
        if (!isBlank(reference)) {
            int slash = reference.lastIndexOf('/');
            return slash >= 0 ? reference.substring(slash + 1) : reference;
        }
        return element instanceof GroupDef ? "group" : "control";
    }

    private String dataTypeName(int dataType) {
        return switch (dataType) {
            case Constants.DATATYPE_TEXT -> "string";
            case Constants.DATATYPE_INTEGER -> "int";
            case Constants.DATATYPE_DECIMAL -> "decimal";
            case Constants.DATATYPE_DATE -> "date";
            case Constants.DATATYPE_TIME -> "time";
            case Constants.DATATYPE_DATE_TIME -> "dateTime";
            case Constants.DATATYPE_CHOICE -> "select1";
            case Constants.DATATYPE_MULTIPLE_ITEMS -> "select";
            case Constants.DATATYPE_BOOLEAN -> "boolean";
            case Constants.DATATYPE_GEOPOINT -> "geopoint";
            case Constants.DATATYPE_BARCODE -> "barcode";
            case Constants.DATATYPE_BINARY -> "binary";
            case Constants.DATATYPE_LONG -> "long";
            case Constants.DATATYPE_GEOSHAPE -> "geoshape";
            case Constants.DATATYPE_GEOTRACE -> "geotrace";
            default -> null;
        };
    }

    public List<ControlInfo> getControlsByType(BodyExtractionResult result, String controlType) {
        return result.getControls().stream()
                .filter(c -> controlType != null && controlType.equalsIgnoreCase(c.getControlType()))
                .collect(Collectors.toList());
    }

    public List<ControlInfo> getInputControls(BodyExtractionResult result) {
        return result.getControls().stream().filter(ControlInfo::isInput).collect(Collectors.toList());
    }

    public List<ControlInfo> getSelectControls(BodyExtractionResult result) {
        return result.getControls().stream().filter(ControlInfo::isSelect).collect(Collectors.toList());
    }

    public List<ControlInfo> getGroupControls(BodyExtractionResult result) {
        return result.getControls().stream().filter(ControlInfo::isGroup).collect(Collectors.toList());
    }

    public List<ControlInfo> getRepeatControls(BodyExtractionResult result) {
        return result.getControls().stream().filter(ControlInfo::isRepeat).collect(Collectors.toList());
    }

    public List<ControlInfo> getControlsWithAppearance(BodyExtractionResult result) {
        return result.getControls().stream().filter(ControlInfo::hasAppearance).collect(Collectors.toList());
    }

    public List<ControlInfo> getControlsWithConstraints(BodyExtractionResult result) {
        return result.getControls().stream()
                .filter(c -> c.getBind() != null && c.getBind().hasConstraint())
                .collect(Collectors.toList());
    }

    public List<ControlInfo> getControlsWithRelevance(BodyExtractionResult result) {
        return result.getControls().stream()
                .filter(c -> c.getBind() != null && c.getBind().hasRelevant())
                .collect(Collectors.toList());
    }

    public List<ControlInfo> getControlsWithCalculations(BodyExtractionResult result) {
        return result.getControls().stream()
                .filter(c -> c.getBind() != null && c.getBind().hasCalculate())
                .collect(Collectors.toList());
    }

    /**
     * Gets the control type for a specific xpath reference from a form definition.
     */
    public String getControlType(FormDef formDef, String xpathReference) {
        if (formDef == null || isBlank(xpathReference)) {
            return null;
        }
        BodyExtractionResult result = extractBody(formDef);
        ControlInfo control = result.getControlsByRef().get(normalizeReference(xpathReference));
        return control != null ? control.getControlType() : null;
    }

    private static String appendPath(String parent, String child) {
        if (isBlank(parent)) return child == null ? "" : child;
        if (isBlank(child)) return parent;
        return parent + "/" + child;
    }

    private static String attribute(Map<String, String> attributes, String... names) {
        if (attributes == null) {
            return null;
        }
        for (String name : names) {
            String value = attributes.get(name);
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String normalizeReference(String reference) {
        return reference == null ? null : reference.trim()
                .replaceAll("\\[(?:-?\\d+|@template)\\]", "")
                .replaceAll("/{2,}", "/");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return null;
    }

    @lombok.Data
    @lombok.Builder
    public static class BodyExtractionResult {
        private String formId;
        private List<ControlInfo> controls;
        private Map<String, ControlInfo> controlsByRef;

        public static BodyExtractionResult empty() {
            return BodyExtractionResult.builder()
                    .formId("")
                    .controls(new ArrayList<>())
                    .controlsByRef(new HashMap<>())
                    .build();
        }

        public int getControlCount() { return controls != null ? controls.size() : 0; }
    }

    @lombok.Data
    @lombok.Builder
    public static class ControlInfo {
        private String nodeName;
        private String xpathReference;
        private String controlType;
        private int depth;
        private String parentPath;
        private IFormElement control;
        private String label;
        private String hint;
        private String appearance;
        private BindInfo bind;
        private List<ChoiceInfo> choices;
        private List<ControlInfo> children;
        private Map<String, ControlInfo> childrenByRef;
        private String inputMode;
        private String mediaType;
        private String rangeStart;
        private String rangeEnd;
        private String rangeStep;
        private String selectionMode;

        public boolean hasLabel() { return !isBlank(label); }
        public boolean hasHint() { return !isBlank(hint); }
        public boolean hasAppearance() { return !isBlank(appearance); }
        public boolean hasBind() { return bind != null; }
        public boolean hasChoices() { return choices != null && !choices.isEmpty(); }
        public boolean hasChildren() { return children != null && !children.isEmpty(); }
        public boolean isGroup() { return control instanceof GroupDef && !((GroupDef) control).getRepeat(); }
        public boolean isRepeat() { return control instanceof GroupDef && ((GroupDef) control).getRepeat(); }
        public boolean isInput() { return control instanceof QuestionDef && !isSelect(); }
        public boolean isSelect() {
            if (!(control instanceof QuestionDef)) return false;
            int type = ((QuestionDef) control).getControlType();
            return type == Constants.CONTROL_SELECT_ONE
                    || type == Constants.CONTROL_SELECT_MULTI
                    || type == Constants.CONTROL_RANK;
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class BindInfo {
        private String dataType;
        private String constraint;
        private String constraintMessage;
        private String relevant;
        private String calculate;
        private String required;
        private String readOnly;
        private boolean attachment;
        private String attachmentMediaType;
        private String validation;
        private String validationMessage;

        public boolean hasConstraint() { return !isBlank(constraint); }
        public boolean hasRelevant() { return !isBlank(relevant); }
        public boolean hasCalculate() { return !isBlank(calculate); }
        public boolean hasRequired() { return !isBlank(required); }
        public boolean hasReadOnly() { return !isBlank(readOnly); }
        public boolean isAttachment() { return attachment; }
        public boolean hasValidation() { return !isBlank(validation); }
    }

    @lombok.Data
    @lombok.Builder
    public static class ChoiceInfo {
        private String value;
        private String label;
    }
}
