package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javarosa.core.model.Constants;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.IFormElement;
import org.javarosa.core.model.ItemsetBinding;
import org.javarosa.core.model.QuestionDef;
import org.javarosa.core.model.SelectChoice;
import org.javarosa.core.model.instance.FormInstance;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.services.locale.Localizer;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class XFormChoiceExtractor {

    private final JavaRosaFormLoader formLoader;

    /** Extracts select choices from JavaRosa's QuestionDef tree. */
    public ChoiceExtractionResult extractChoices(FormDef formDef) {
        if (formDef == null) {
            return ChoiceExtractionResult.empty();
        }
        List<ChoiceList> choiceLists = new ArrayList<>();
        Map<String, ChoiceList> choiceListsByName = new HashMap<>();

        Map<String, Map<String, String>> bindIndex = formLoader.extractBindAttributes(formDef);
        Map<String, Map<String, RawChoiceMetadata>> rawChoiceMetadata = extractRawChoiceMetadata(formDef);
        traverseForChoices(
                formDef,
                formDef.getChildren(),
                choiceLists,
                choiceListsByName,
                bindIndex,
                rawChoiceMetadata);

        String formId = formDef.getName();
        log.debug("Extracted {} choice lists from form: {}", choiceLists.size(), formId);
        return ChoiceExtractionResult.builder()
                .formId(formId)
                .choiceLists(choiceLists)
                .choiceListsByName(choiceListsByName)
                .build();
    }

    public ChoiceExtractionResult extractChoices(String xmlContent) {
        FormDef formDef = formLoader.loadFormDef(xmlContent);
        return extractChoices(formDef);
    }

    private void traverseForChoices(FormDef formDef,
                                    List<IFormElement> elements,
                                    List<ChoiceList> choiceLists,
                                    Map<String, ChoiceList> choiceListsByName,
                                    Map<String, Map<String, String>> bindIndex,
                                    Map<String, Map<String, RawChoiceMetadata>> rawChoiceMetadata) {
        if (elements == null) {
            return;
        }
        for (IFormElement element : elements) {
            if (element instanceof QuestionDef) {
                QuestionDef question = (QuestionDef) element;
                if (isSelectQuestion(question)) {
                    ChoiceList choiceList = extractChoiceList(formDef, question, bindIndex, rawChoiceMetadata);
                    choiceLists.add(choiceList);
                    choiceListsByName.put(choiceList.getListName(), choiceList);
                    if (!isBlank(choiceList.getXpathReference())) {
                        choiceListsByName.put(choiceList.getXpathReference(), choiceList);
                        choiceListsByName.put(normalizeReference(choiceList.getXpathReference()), choiceList);
                    }
                }
            }
            traverseForChoices(
                    formDef,
                    element.getChildren(),
                    choiceLists,
                    choiceListsByName,
                    bindIndex,
                    rawChoiceMetadata);
        }
    }

    private ChoiceList extractChoiceList(FormDef formDef,
                                         QuestionDef question,
                                         Map<String, Map<String, String>> bindIndex,
                                         Map<String, Map<String, RawChoiceMetadata>> rawChoiceMetadata) {
        String xpathReference = getXPathReference(question);
        String nodeName = getNodeName(question, xpathReference);
        ItemsetBinding dynamicChoices = question.getDynamicChoices();
        String dynamicNodeset = dynamicChoices == null
                ? null
                : expressionOrReference(dynamicChoices.nodesetExpr, dynamicChoices.nodesetRef);
        String listName = firstNonBlank(dynamicNodeset, nodeName);

        Map<String, RawChoiceMetadata> rawChoicesForList = rawChoiceMetadata.getOrDefault(
                normalizeReference(xpathReference),
                Map.of());

        List<Choice> choices = new ArrayList<>();
        if (question.getChoices() != null) {
            for (SelectChoice choice : question.getChoices()) {
                RawChoiceMetadata raw = rawChoicesForList.get(choice.getValue());

                String labelRef = firstNonBlank(
                        raw == null ? null : raw.labelRef,
                        toITextReference(choice.getTextID()));

                String image = firstNonBlank(
                        raw == null ? null : raw.image,
                        resolveChoiceForm(formDef, choice, "image"));
                String audio = firstNonBlank(
                        raw == null ? null : raw.audio,
                        resolveChoiceForm(formDef, choice, "audio"));
                String video = firstNonBlank(
                        raw == null ? null : raw.video,
                        resolveChoiceForm(formDef, choice, "video"));

                choices.add(Choice.builder()
                        .value(choice.getValue())
                        .label(resolveChoiceForm(formDef, choice, null))
                        .labelRef(labelRef)
                        .image(image)
                        .imageRef(image)
                        .audio(audio)
                        .audioRef(audio)
                        .video(video)
                        .videoRef(video)
                        .build());
            }
        }

        ItemsetInfo itemset = dynamicChoices == null ? null : ItemsetInfo.builder()
                .nodeset(expressionOrReference(dynamicChoices.nodesetExpr, dynamicChoices.nodesetRef))
                .valueRef(expressionOrReference(dynamicChoices.valueExpr, dynamicChoices.valueRef))
                .labelRef(expressionOrReference(dynamicChoices.labelExpr, dynamicChoices.labelRef))
                .imageRef(null)
                .audioRef(null)
                .videoRef(null)
                .build();

        return ChoiceList.builder()
                .listName(isBlank(listName) ? nodeName : listName)
                .nodeName(nodeName)
                .xpathReference(xpathReference)
                .choices(choices)
                .itemset(itemset)
                .bind(extractBindInfo(
                        resolveInstanceNode(formDef, question),
                        bindIndex.getOrDefault(normalizeReference(xpathReference), Map.of())))
                .selectionMode(question.getControlType() == Constants.CONTROL_SELECT_ONE ? "single" : "multiple")
                .build();
    }

    private boolean isSelectQuestion(QuestionDef question) {
        int type = question.getControlType();
        return type == Constants.CONTROL_SELECT_ONE
                || type == Constants.CONTROL_SELECT_MULTI
                || type == Constants.CONTROL_RANK
                || question.getChoices() != null
                || question.getDynamicChoices() != null;
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

    private TreeElement resolveInstanceNode(FormDef formDef, IFormElement element) {
        if (formDef == null || formDef.getMainInstance() == null || element.getBind() == null) {
            return null;
        }
        try {
            return formDef.getMainInstance().resolveReference(FormInstance.unpackReference(element.getBind()));
        } catch (RuntimeException e) {
            log.debug("Could not resolve choice-list instance node: {}", e.getMessage());
            return null;
        }
    }

    /**
     * JavaRosa's SelectChoice retains the value and localized text id, but direct
     * XForm item media elements such as <image> and <audio> are not guaranteed to
     * survive in that model. Merge those source-level details from the canonical
     * XML retained by JavaRosaFormLoader.
     */
    private Map<String, Map<String, RawChoiceMetadata>> extractRawChoiceMetadata(FormDef formDef) {
        String xmlContent;
        try {
            xmlContent = formLoader.getXmlContent(formDef);
        } catch (RuntimeException e) {
            log.debug("Unable to retrieve source XML for choice metadata: {}", e.getMessage());
            return Map.of();
        }

        if (isBlank(xmlContent)) {
            return Map.of();
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);

            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // Continue with the remaining parser hardening options.
            }
            try {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            } catch (Exception ignored) {
                // Continue with the remaining parser hardening options.
            }
            try {
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Exception ignored) {
                // Continue with the remaining parser hardening options.
            }
            try {
                factory.setXIncludeAware(false);
            } catch (UnsupportedOperationException ignored) {
                // Not every parser implementation supports XInclude configuration.
            }
            factory.setExpandEntityReferences(false);
            try {
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            } catch (IllegalArgumentException ignored) {
                // Some XML parser implementations do not expose these attributes.
            }

            Document document = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xmlContent)));

            Map<String, Map<String, RawChoiceMetadata>> result = new LinkedHashMap<>();
            collectRawChoiceMetadata(document.getDocumentElement(), result);
            return result;
        } catch (Exception e) {
            log.debug("Unable to parse source XML for choice metadata: {}", e.getMessage());
            return Map.of();
        }
    }

    private void collectRawChoiceMetadata(
            Element element,
            Map<String, Map<String, RawChoiceMetadata>> result) {

        String localName = localName(element);
        if ("select1".equals(localName) || "select".equals(localName)) {
            String xpathReference = firstNonBlank(
                    attributeValue(element, "ref"),
                    attributeValue(element, "nodeset"));

            if (!isBlank(xpathReference)) {
                Map<String, RawChoiceMetadata> choicesForList = result.computeIfAbsent(
                        normalizeReference(xpathReference),
                        ignored -> new LinkedHashMap<>());

                for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (!(child instanceof Element) || !"item".equals(localName((Element) child))) {
                        continue;
                    }

                    Element item = (Element) child;
                    String value = directChildText(item, "value");
                    if (isBlank(value)) {
                        continue;
                    }

                    Element labelElement = directChildElement(item, "label");
                    String labelRef = labelElement == null
                            ? null
                            : firstNonBlank(
                                    attributeValue(labelElement, "ref"),
                                    attributeValue(labelElement, "nodeset"));

                    choicesForList.put(value, new RawChoiceMetadata(
                            labelRef,
                            directChildValue(item, "image"),
                            directChildValue(item, "audio"),
                            directChildValue(item, "video")));
                }
            }
        }

        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                collectRawChoiceMetadata((Element) child, result);
            }
        }
    }

    private String directChildValue(Element parent, String childName) {
        Element child = directChildElement(parent, childName);
        if (child == null) {
            return null;
        }
        return firstNonBlank(
                child.getTextContent() == null ? null : child.getTextContent().trim(),
                attributeValue(child, "ref"));
    }

    private String directChildText(Element parent, String childName) {
        Element child = directChildElement(parent, childName);
        if (child == null || child.getTextContent() == null) {
            return null;
        }
        String value = child.getTextContent().trim();
        return isBlank(value) ? null : value;
    }

    private Element directChildElement(Element parent, String childName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && childName.equals(localName((Element) child))) {
                return (Element) child;
            }
        }
        return null;
    }

    private String attributeValue(Element element, String name) {
        if (element == null || !element.hasAttribute(name)) {
            return null;
        }
        String value = element.getAttribute(name);
        return isBlank(value) ? null : value;
    }

    private String localName(Element element) {
        String local = element.getLocalName();
        if (!isBlank(local)) {
            return local;
        }
        String nodeName = element.getNodeName();
        int colon = nodeName == null ? -1 : nodeName.indexOf(':');
        return colon >= 0 ? nodeName.substring(colon + 1) : nodeName;
    }

    private String toITextReference(String textId) {
        if (isBlank(textId)) {
            return null;
        }
        if (textId.startsWith("jr:itext(")) {
            return textId;
        }
        return "jr:itext('" + textId + "')";
    }

    private String resolveChoiceForm(FormDef formDef, SelectChoice choice, String form) {
        if (form == null) {
            return firstNonBlank(localizedText(formDef, choice.getTextID()), choice.getLabelInnerText());
        }
        if (isBlank(choice.getTextID())) {
            return null;
        }
        return localizedText(formDef, choice.getTextID() + ";" + form);
    }

    private String localizedText(FormDef formDef, String textId) {
        if (formDef == null || isBlank(textId) || formDef.getLocalizer() == null) {
            return null;
        }
        Localizer localizer = formDef.getLocalizer();
        String locale = firstNonBlank(localizer.getLocale(), localizer.getDefaultLocale());
        if (isBlank(locale)) {
            return null;
        }
        try {
            return localizer.getText(textId, locale);
        } catch (RuntimeException e) {
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
                // Fall through.
            }
        }
        int slash = reference == null ? -1 : reference.lastIndexOf('/');
        return slash >= 0 ? reference.substring(slash + 1) : reference;
    }

    private String expressionOrReference(Object expression, TreeReference reference) {
        if (expression != null) {
            return expression.toString();
        }
        return reference == null ? null : reference.toString();
    }

    private String dataTypeName(int dataType) {
        return switch (dataType) {
            case Constants.DATATYPE_CHOICE -> "select1";
            case Constants.DATATYPE_MULTIPLE_ITEMS -> "select";
            case Constants.DATATYPE_TEXT -> "string";
            case Constants.DATATYPE_INTEGER -> "int";
            case Constants.DATATYPE_DECIMAL -> "decimal";
            case Constants.DATATYPE_DATE -> "date";
            case Constants.DATATYPE_TIME -> "time";
            case Constants.DATATYPE_DATE_TIME -> "dateTime";
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

    public List<ChoiceList> getAllChoiceLists(ChoiceExtractionResult result) {
        return result.getChoiceLists();
    }

    public ChoiceList getChoiceList(ChoiceExtractionResult result, String listName) {
        return result.getChoiceListsByName().get(listName);
    }

    public List<Choice> getChoices(ChoiceExtractionResult result, String listName) {
        ChoiceList list = result.getChoiceListsByName().get(listName);
        return list == null ? new ArrayList<>() : list.getChoices();
    }

    public List<ChoiceList> getStaticChoiceLists(ChoiceExtractionResult result) {
        return result.getChoiceLists().stream().filter(ChoiceList::isStatic).collect(Collectors.toList());
    }

    public List<ChoiceList> getDynamicChoiceLists(ChoiceExtractionResult result) {
        return result.getChoiceLists().stream().filter(ChoiceList::isDynamic).collect(Collectors.toList());
    }

    public int getTotalChoiceCount(ChoiceExtractionResult result) {
        return result.getChoiceLists().stream().mapToInt(ChoiceList::getChoiceCount).sum();
    }

    public List<String> validateChoiceReferences(ChoiceExtractionResult result) {
        List<String> errors = new ArrayList<>();
        for (ChoiceList list : result.getChoiceLists()) {
            Map<String, Long> valueCounts = list.getChoices().stream()
                    .filter(choice -> choice.getValue() != null)
                    .collect(Collectors.groupingBy(Choice::getValue, Collectors.counting()));
            valueCounts.forEach((value, count) -> {
                if (count > 1) {
                    errors.add(String.format("Duplicate choice value '%s' in list '%s'", value, list.getListName()));
                }
            });
            for (Choice choice : list.getChoices()) {
                if (isBlank(choice.getValue())) {
                    errors.add(String.format("Empty choice value in list '%s'", list.getListName()));
                }
            }
        }
        return errors;
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
    public static class ChoiceExtractionResult {
        private String formId;
        private List<ChoiceList> choiceLists;
        private Map<String, ChoiceList> choiceListsByName;

        public static ChoiceExtractionResult empty() {
            return ChoiceExtractionResult.builder()
                    .formId("")
                    .choiceLists(new ArrayList<>())
                    .choiceListsByName(new HashMap<>())
                    .build();
        }

        public int getChoiceListCount() { return choiceLists != null ? choiceLists.size() : 0; }
    }

    @lombok.Data
    @lombok.Builder
    public static class ChoiceList {
        private String listName;
        private String nodeName;
        private String xpathReference;
        private List<Choice> choices;
        private ItemsetInfo itemset;
        private BindInfo bind;
        private String selectionMode;

        public boolean isDynamic() { return itemset != null; }
        public boolean isStatic() { return itemset == null; }
        public int getChoiceCount() { return choices != null ? choices.size() : 0; }
        
        public String getSelectType() {
            return "single".equals(selectionMode) ? "select1" : "select";
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class Choice {
        private String value;
        private String label;
        private String labelRef;
        private String image;
        private String imageRef;
        private String audio;
        private String audioRef;
        private String video;
        private String videoRef;

        public boolean hasLabel() { return !isBlank(label); }
        public boolean hasMedia() { return !isBlank(image) || !isBlank(audio) || !isBlank(video); }
    }

    @lombok.Data
    @lombok.Builder
    public static class ItemsetInfo {
        private String nodeset;
        private String valueRef;
        private String labelRef;
        private String imageRef;
        private String audioRef;
        private String videoRef;

        public boolean hasNodeset() { return !isBlank(nodeset); }
        public boolean hasValueRef() { return !isBlank(valueRef); }
        public boolean hasLabelRef() { return !isBlank(labelRef); }
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

    // Additional methods expected by tests
    public List<String> getChoiceListNames(FormDef formDef) {
        ChoiceExtractionResult result = extractChoices(formDef);
        return result.getChoiceLists().stream()
                .map(ChoiceList::getXpathReference)
                .collect(Collectors.toList());
    }

    public List<Choice> getChoicesForXPath(FormDef formDef, String xpathReference) {
        if (isBlank(xpathReference)) {
            return new ArrayList<>();
        }

        ChoiceExtractionResult result = extractChoices(formDef);
        String normalizedReference = normalizeReference(xpathReference);

        ChoiceList list = result.getChoiceListsByName().get(xpathReference);
        if (list == null) {
            list = result.getChoiceListsByName().get(normalizedReference);
        }
        if (list == null) {
            list = result.getChoiceLists().stream()
                    .filter(choiceList -> normalizedReference.equals(
                            normalizeReference(choiceList.getXpathReference())))
                    .findFirst()
                    .orElse(null);
        }

        return list != null ? list.getChoices() : new ArrayList<>();
    }

    private static class RawChoiceMetadata {
        private final String labelRef;
        private final String image;
        private final String audio;
        private final String video;

        private RawChoiceMetadata(String labelRef, String image, String audio, String video) {
            this.labelRef = labelRef;
            this.image = image;
            this.audio = audio;
            this.video = video;
        }
    }
}