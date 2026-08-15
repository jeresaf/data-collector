package org.odk.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javarosa.core.model.Constants;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.instance.TreeElement;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses submitted ODK instance XML and converts the JavaRosa instance tree to
 * the platform's nested JSON representation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubmissionXmlParser {

    private final JavaRosaFormLoader formLoader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse a submission using an already-loaded FormDef. This compatibility
     * method keeps the previous tolerant behavior for existing callers/tests.
     */
    public JsonNode parseSubmission(String xmlContent, FormDef formDef) {
        try {
            String formXml = formLoader.getXmlContent(formDef);
            if (formXml == null || formXml.isBlank()) {
                log.warn("Original form XML not found in store");
                return objectMapper.createObjectNode();
            }
            FormDef parsed = JavaRosaFormLoader.parseRestoredInstance(formXml, xmlContent);
            TreeElement root = parsed.getMainInstance() == null ? null : parsed.getMainInstance().getRoot();
            return root == null ? objectMapper.createObjectNode() : convertToJson(root);
        } catch (Exception e) {
            log.error("Failed to parse submission XML: {}", e.getMessage(), e);
            return objectMapper.createObjectNode();
        }
    }

    /**
     * Strict production path: parse the submitted instance with JavaRosa's
     * two-reader parser, run the full JavaRosa validation sweep, and return the
     * validated instance plus nested JSON. Invalid structure, types, required
     * values and constraints fail before persistence.
     */
    public ValidatedSubmission parseAndValidate(
            String formXml,
            String instanceXml,
            Map<String, byte[]> externalDatasets) {

        FormDef formDef = formLoader.loadFormDef(formXml, instanceXml, externalDatasets);

        TreeElement root = formDef.getMainInstance() == null
                ? null
                : formDef.getMainInstance().getRoot();
        if (root == null) {
            throw new IllegalArgumentException("Submission instance has no root element");
        }

        /*
         * JavaRosa's restored-instance parser intentionally converts lexical
         * values into the bound data type. For several types (for example int,
         * decimal and date), an invalid lexical value becomes null. Because an
         * optional null answer is otherwise valid, FormDef.validate() cannot by
         * itself distinguish "omitted" from "present but malformed". Compare
         * non-blank submitted leaves to the restored JavaRosa tree first so a
         * malformed typed value is rejected without making optional fields
         * required.
         */
        validateRestoredLexicalValues(parseSecureDocument(instanceXml).getDocumentElement(), root, "");

        var outcome = formDef.validate();
        if (outcome != null) {
            throw new IllegalArgumentException(
                    "Submission failed JavaRosa validation (outcome=" + outcome.outcome + ")");
        }

        return new ValidatedSubmission(formDef, convertToJson(root));
    }

    /**
     * Read the OpenRosa submission envelope with a namespace-aware XML parser.
     * DTDs and external entities are disabled so identifier extraction cannot
     * become an XXE path. This replaces the former string/indexOf scanning.
     */
    public SubmissionEnvelope parseEnvelope(String xmlContent) {
        if (xmlContent == null || xmlContent.isBlank()) {
            throw new IllegalArgumentException("Submission XML cannot be empty");
        }

        try {
            Document document = parseSecureDocument(xmlContent);
            Element root = document.getDocumentElement();
            if (root == null) {
                throw new IllegalArgumentException("Submission XML has no document element");
            }

            String formId = trimToNull(root.getAttribute("id"));
            String version = trimToNull(root.getAttribute("version"));
            String instanceId = firstElementText(root, "instanceID", "instanceId");
            String deprecatedId = firstElementText(root, "deprecatedID", "deprecatedId");

            if (formId == null) {
                throw new IllegalArgumentException("Submission root is missing form id");
            }

            return new SubmissionEnvelope(formId, version, instanceId, deprecatedId);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid or unsafe submission XML: " + safeMessage(e), e);
        }
    }


    private Document parseSecureDocument(String xmlContent) {
        if (xmlContent == null || xmlContent.isBlank()) {
            throw new IllegalArgumentException("Submission XML cannot be empty");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid or unsafe submission XML: " + safeMessage(e), e);
        }
    }

    private void validateRestoredLexicalValues(
            Element submitted, TreeElement restored, String parentPath) {

        if (submitted == null || restored == null) {
            return;
        }

        String currentName = elementName(submitted);
        String currentPath = parentPath.isEmpty()
                ? currentName
                : parentPath + "/" + currentName;

        List<Element> submittedChildren = elementChildren(submitted);
        if (submittedChildren.isEmpty()) {
            String raw = submitted.getTextContent();
            if (raw != null && !raw.trim().isEmpty()
                    && restored.isRelevant()
                    && restored.getValue() == null
                    && restored.getDataType() != Constants.DATATYPE_TEXT
                    && restored.getDataType() != Constants.DATATYPE_NULL) {
                throw new IllegalArgumentException(
                        "Invalid value for typed field '" + currentPath + "'");
            }
            return;
        }

        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (Element submittedChild : submittedChildren) {
            String name = elementName(submittedChild);
            int occurrence = occurrences.getOrDefault(name, 0);
            occurrences.put(name, occurrence + 1);

            TreeElement restoredChild = restoredChild(restored, name, occurrence);
            // JavaRosa intentionally ignores unknown instance nodes while
            // restoring legacy-compatible submissions. Preserve that behavior
            // here rather than making this lexical check a structure validator.
            if (restoredChild != null) {
                validateRestoredLexicalValues(submittedChild, restoredChild, currentPath);
            }
        }
    }

    private TreeElement restoredChild(TreeElement parent, String name, int occurrence) {
        int seen = 0;
        for (int i = 0; i < parent.getNumChildren(); i++) {
            TreeElement child = parent.getChildAt(i);
            if (child.getMult() == org.javarosa.core.model.instance.TreeReference.INDEX_TEMPLATE) {
                continue;
            }
            if (name.equals(child.getName())) {
                if (seen == occurrence) {
                    return child;
                }
                seen++;
            }
        }
        return null;
    }

    private List<Element> elementChildren(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    private String elementName(Element element) {
        String local = element.getLocalName();
        if (local != null && !local.isBlank()) {
            return local;
        }
        String name = element.getNodeName();
        int colon = name == null ? -1 : name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private String firstElementText(Element root, String... localNames) {
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node node = all.item(i);
            String local = node.getLocalName();
            String name = local != null ? local : node.getNodeName();
            if (name != null && name.contains(":")) {
                name = name.substring(name.indexOf(':') + 1);
            }
            for (String expected : localNames) {
                if (expected.equals(name)) {
                    return trimToNull(node.getTextContent());
                }
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }

    /** Convert the main JavaRosa instance into nested JSON. */
    private JsonNode convertToJson(TreeElement rootElement) {
        return convertContainer(rootElement);
    }

    /**
     * Recursively convert a container. Repeat nodes are identified by JavaRosa's
     * repeatable flag or multiple sibling instances. Template nodes are omitted.
     */
    private ObjectNode convertContainer(TreeElement element) {
        ObjectNode result = objectMapper.createObjectNode();
        Map<String, List<TreeElement>> groupedChildren = new LinkedHashMap<>();

        for (int i = 0; i < element.getNumChildren(); i++) {
            TreeElement child = element.getChildAt(i);
            if (child.getMult() == org.javarosa.core.model.instance.TreeReference.INDEX_TEMPLATE) {
                continue;
            }
            groupedChildren.computeIfAbsent(child.getName(), ignored -> new ArrayList<>()).add(child);
        }

        for (Map.Entry<String, List<TreeElement>> entry : groupedChildren.entrySet()) {
            String childName = entry.getKey();
            List<TreeElement> children = entry.getValue();
            if (children.isEmpty()) continue;

            boolean repeatable = children.size() > 1
                    || children.stream().anyMatch(TreeElement::isRepeatable);

            if (repeatable) {
                ArrayNode arrayNode = objectMapper.createArrayNode();
                for (TreeElement child : children) {
                    JsonNode childValue = convertNode(child);
                    if (childValue != null) {
                        arrayNode.add(childValue);
                    } else if (!child.isLeaf()) {
                        arrayNode.add(objectMapper.createObjectNode());
                    }
                }
                result.set(childName, arrayNode);
                continue;
            }

            JsonNode childValue = convertNode(children.get(0));
            if (childValue != null) result.set(childName, childValue);
        }

        return result;
    }

    private JsonNode convertNode(TreeElement element) {
        if (!element.isLeaf()) return convertContainer(element);
        if (element.getValue() == null) return null;

        String stringValue = element.getValue().uncast().getString();
        if (stringValue == null || stringValue.isEmpty()) return null;
        return objectMapper.getNodeFactory().textNode(stringValue);
    }

    /** Backward-compatible identifier methods now use the secure parser. */
    public String extractInstanceId(String xmlContent) {
        try {
            return parseEnvelope(xmlContent).instanceId();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String extractFormId(String xmlContent) {
        try {
            return parseEnvelope(xmlContent).formId();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String extractDeprecatedId(String xmlContent) {
        try {
            return parseEnvelope(xmlContent).deprecatedId();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record SubmissionEnvelope(
            String formId,
            String version,
            String instanceId,
            String deprecatedId) {
    }

    public record ValidatedSubmission(FormDef formDef, JsonNode jsonContent) {
    }
}
