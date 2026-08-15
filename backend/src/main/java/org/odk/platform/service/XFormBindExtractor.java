package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javarosa.core.model.Constants;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.instance.FormInstance;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.xpath.XPathParseTool;
import org.javarosa.xpath.parser.XPathSyntaxException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class XFormBindExtractor {

    private final JavaRosaFormLoader formLoader;

    public BindExtractionResult extractBinds(String xmlContent) {
        return extractBinds(formLoader.loadFormDef(xmlContent));
    }

    /**
     * JavaRosa applies bind state to the main instance tree. Exact source
     * expressions are merged from the canonical XML because 4.3.2 does not
     * expose every known bind expression through TreeElement.
     */
    public BindExtractionResult extractBinds(FormDef formDef) {
        if (formDef == null) {
            return BindExtractionResult.empty();
        }
        List<BindInfo> binds = new ArrayList<>();
        Map<String, BindInfo> bindsByNode = new HashMap<>();

        Map<String, Map<String, String>> sourceBinds = formLoader.extractBindAttributes(formDef);
        Map<String, String> mediaTypes = formLoader.extractMediaTypes(formDef);
        FormInstance mainInstance = formDef.getMainInstance();
        TreeElement root = mainInstance == null ? null : mainInstance.getRoot();
        if (root != null) {
            traverseInstanceTree(root, binds, bindsByNode, "", sourceBinds, mediaTypes);
        }

        String formId = formDef.getName();
        log.debug("Extracted {} binds from form: {}", binds.size(), formId);
        return BindExtractionResult.builder()
                .formId(formId)
                .binds(binds)
                .bindsByNode(bindsByNode)
                .build();
    }

    private void traverseInstanceTree(TreeElement element,
                                      List<BindInfo> binds,
                                      Map<String, BindInfo> bindsByNode,
                                      String parentPath,
                                      Map<String, Map<String, String>> sourceBinds,
                                      Map<String, String> mediaTypes) {
        if (element == null) {
            return;
        }

        String nodeName = blankToDefault(element.getName(), "root");
        String nodePath = parentPath.isEmpty() ? nodeName : parentPath + "/" + nodeName;
        List<TreeElement> bindAttributes = element.getBindAttributes();
        TreeReference reference = element.getRef();
        Map<String, String> sourceAttributes = reference == null
                ? Map.of()
                : sourceBinds.getOrDefault(normalizeReference(reference.toString()), Map.of());

        if (!sourceAttributes.isEmpty()
                || (bindAttributes != null && !bindAttributes.isEmpty())
                || element.getDataType() != Constants.DATATYPE_NULL) {
            BindInfo bindInfo = extractBindInfo(nodePath, nodeName, element, sourceAttributes, mediaTypes);
            binds.add(bindInfo);
            bindsByNode.put(bindInfo.getNodePath(), bindInfo);
            if (bindInfo.getXpathReference() != null && !bindInfo.getXpathReference().isBlank()) {
                bindsByNode.putIfAbsent(bindInfo.getXpathReference(), bindInfo);
            }
        }

        for (int i = 0; i < element.getNumChildren(); i++) {
            traverseInstanceTree(element.getChildAt(i), binds, bindsByNode, nodePath, sourceBinds, mediaTypes);
        }
    }

    private BindInfo extractBindInfo(String nodePath,
                                     String nodeName,
                                     TreeElement element,
                                     Map<String, String> sourceAttributes,
                                     Map<String, String> mediaTypes) {
        String dataType = firstNonBlank(
                attribute(sourceAttributes, "type"),
                element.getBindAttributeValue(null, "type"),
                getDataTypeName(element.getDataType()));

        String constraint = firstNonBlank(
                attribute(sourceAttributes, "constraint"),
                element.getBindAttributeValue(null, "constraint"));
        String constraintMessage = firstNonBlank(
                attribute(sourceAttributes, "constraintMsg", "constraintmsg"),
                element.getBindAttributeValue(null, "constraintMsg"),
                element.getBindAttributeValue(null, "constraintmsg"));
        String relevant = firstNonBlank(
                attribute(sourceAttributes, "relevant"),
                element.getBindAttributeValue(null, "relevant"));
        String calculate = firstNonBlank(
                attribute(sourceAttributes, "calculate"),
                element.getBindAttributeValue(null, "calculate"));
        String required = firstNonBlank(
                attribute(sourceAttributes, "required"),
                element.getBindAttributeValue(null, "required"));
        String readOnly = firstNonBlank(
                attribute(sourceAttributes, "readonly", "readOnly"),
                element.getBindAttributeValue(null, "readonly"),
                element.getBindAttributeValue(null, "readOnly"));
        String validationExpression = firstNonBlank(
                attribute(sourceAttributes, "validate"),
                element.getBindAttributeValue(null, "validate"));
        String validationMessage = firstNonBlank(
                attribute(sourceAttributes, "validateMsg", "validatemsg"),
                element.getBindAttributeValue(null, "validateMsg"),
                element.getBindAttributeValue(null, "validatemsg"));

        // Runtime-state fallbacks are useful when JavaRosa has normalized a boolean bind.
        if (isBlank(relevant) && !element.isRelevant()) {
            relevant = "false()";
        }
        // TreeElement 4.3.2 has setRequired(...) but no public isRequired() getter.
        // Preserve the original expression only when it is available as bind metadata.
        if (isBlank(readOnly) && !element.isEnabled()) {
            readOnly = "true()";
        }

        TreeReference ref = element.getRef();
        String xpathReference = ref == null ? null : normalizeReference(ref.toString());
        
        // Look up media type from upload elements in the form body
        String mediaType = firstNonBlank(
                attribute(sourceAttributes, "mediatype", "mediaType"),
                element.getBindAttributeValue(null, "mediatype"),
                element.getBindAttributeValue(null, "mediaType"),
                xpathReference != null ? mediaTypes.get(xpathReference) : null);
        boolean attachment = element.getDataType() == Constants.DATATYPE_BINARY
                || !isBlank(mediaType);

        return BindInfo.builder()
                .nodePath(nodePath)
                .nodeName(nodeName)
                .dataType(dataType)
                .constraint(constraint)
                .constraintMessage(constraintMessage)
                .relevant(relevant)
                .calculate(calculate)
                .required(required)
                .readOnly(readOnly)
                .attachment(attachment)
                .attachmentMediaType(mediaType)
                .validation(validationExpression)
                .validationMessage(validationMessage)
                .xpathReference(xpathReference)
                .build();
    }

    private String getDataTypeName(int dataType) {
        return switch (dataType) {
            case Constants.DATATYPE_TEXT -> "string";
            case Constants.DATATYPE_INTEGER -> "int";
            case Constants.DATATYPE_DECIMAL -> "decimal";
            case Constants.DATATYPE_DATE -> "date";
            case Constants.DATATYPE_DATE_TIME -> "dateTime";
            case Constants.DATATYPE_TIME -> "time";
            case Constants.DATATYPE_CHOICE -> "select1";
            case Constants.DATATYPE_MULTIPLE_ITEMS -> "select";
            case Constants.DATATYPE_BOOLEAN -> "boolean";
            case Constants.DATATYPE_GEOPOINT -> "geopoint";
            case Constants.DATATYPE_BARCODE -> "barcode";
            case Constants.DATATYPE_BINARY -> "binary";
            case Constants.DATATYPE_LONG -> "long";
            case Constants.DATATYPE_GEOSHAPE -> "geoshape";
            case Constants.DATATYPE_GEOTRACE -> "geotrace";
            case Constants.DATATYPE_UNSUPPORTED -> "unsupported";
            default -> null;
        };
    }

    public List<BindInfo> getBindsByType(BindExtractionResult result, String dataType) {
        return result.getBinds().stream()
                .filter(b -> dataType != null && dataType.equalsIgnoreCase(b.getDataType()))
                .collect(Collectors.toList());
    }

    public List<BindInfo> getCalculatedFields(BindExtractionResult result) {
        return result.getBinds().stream().filter(BindInfo::hasCalculate).collect(Collectors.toList());
    }

    public List<BindInfo> getConstrainedFields(BindExtractionResult result) {
        return result.getBinds().stream().filter(BindInfo::hasConstraint).collect(Collectors.toList());
    }

    public List<BindInfo> getRelevantFields(BindExtractionResult result) {
        return result.getBinds().stream().filter(BindInfo::hasRelevant).collect(Collectors.toList());
    }

    public List<BindInfo> getRequiredFields(BindExtractionResult result) {
        return result.getBinds().stream().filter(BindInfo::hasRequired).collect(Collectors.toList());
    }

    public List<BindInfo> getReadOnlyFields(BindExtractionResult result) {
        return result.getBinds().stream().filter(BindInfo::hasReadOnly).collect(Collectors.toList());
    }

    public List<BindInfo> getAttachmentFields(BindExtractionResult result) {
        return result.getBinds().stream().filter(BindInfo::isAttachment).collect(Collectors.toList());
    }

    public List<BindInfo> getValidationFields(BindExtractionResult result) {
        return result.getBinds().stream().filter(BindInfo::hasValidation).collect(Collectors.toList());
    }

    /**
     * Gets the original bind attributes for a specific xpath reference from a form definition.
     */
    public Map<String, String> getBindAttributes(FormDef formDef, String xpathReference) {
        if (formDef == null || isBlank(xpathReference)) {
            return Map.of();
        }
        Map<String, Map<String, String>> sourceBinds = formLoader.extractBindAttributes(formDef);
        return sourceBinds.getOrDefault(normalizeReference(xpathReference), Map.of());
    }

    public List<String> validateXPathExpressions(BindExtractionResult result) {
        List<String> errors = new ArrayList<>();
        for (BindInfo bind : result.getBinds()) {
            validateXPath(bind.getConstraint(), "constraint", bind.getNodePath(), errors);
            validateXPath(bind.getRelevant(), "relevant", bind.getNodePath(), errors);
            validateXPath(bind.getCalculate(), "calculate", bind.getNodePath(), errors);
            validateXPath(bind.getRequired(), "required", bind.getNodePath(), errors);
            validateXPath(bind.getReadOnly(), "readonly", bind.getNodePath(), errors);
        }
        return errors;
    }

    private void validateXPath(String expression, String type, String nodePath, List<String> errors) {
        if (isBlank(expression)) {
            return;
        }
        try {
            XPathParseTool.parseXPath(expression);
        } catch (XPathSyntaxException e) {
            errors.add(String.format("Invalid XPath in %s for node '%s': %s", type, nodePath, e.getMessage()));
        } catch (RuntimeException e) {
            errors.add(String.format("Error parsing %s for node '%s': %s", type, nodePath, e.getMessage()));
        }
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

    private static String blankToDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    @lombok.Data
    @lombok.Builder
    public static class BindExtractionResult {
        private String formId;
        private List<BindInfo> binds;
        private Map<String, BindInfo> bindsByNode;

        public static BindExtractionResult empty() {
            return BindExtractionResult.builder()
                    .formId("")
                    .binds(new ArrayList<>())
                    .bindsByNode(new HashMap<>())
                    .build();
        }

        public int getBindCount() {
            return binds != null ? binds.size() : 0;
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class BindInfo {
        private String nodePath;
        private String nodeName;
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
        private String xpathReference;

        public boolean hasConstraint() { return !isBlank(constraint); }
        public boolean hasRelevant() { return !isBlank(relevant); }
        public boolean hasCalculate() { return !isBlank(calculate); }
        public boolean hasRequired() { return !isBlank(required) && !"false()".equalsIgnoreCase(required.trim()); }
        public boolean hasReadOnly() { return !isBlank(readOnly) && !"false()".equalsIgnoreCase(readOnly.trim()); }
        public boolean isAttachment() { return attachment; }
        public boolean hasValidation() { return !isBlank(validation); }
    }
}
