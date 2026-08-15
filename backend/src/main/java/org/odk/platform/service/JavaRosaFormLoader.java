package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.instance.FormInstance;
import org.javarosa.core.model.instance.InstanceInitializationFactory;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.form.api.FormEntryModel;
import org.javarosa.model.xform.XFormsModule;
import org.javarosa.xform.parse.XFormParser;
import org.odk.platform.exception.FormValidationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads and parses XForms with the JavaRosa 4.3.2 API. */
@Service
@RequiredArgsConstructor
@Slf4j
public class JavaRosaFormLoader {

    // JavaRosa uses a non-blocking global parse lock. Serialize parses here so
    // concurrent web requests wait instead of failing with "Another XForm is being parsed!".
    private static final Object XFORM_PARSER_LOCK = new Object();

    /*
     * JavaRosa keeps the XForms restoration/answer factory in process-global
     * static state (RestoreUtils.xfFact). Restoring a submitted instance with
     * the two-reader XFormParser path needs this factory for every non-text
     * value (int, decimal, date, select, geo, etc.).
     *
     * Do this at the parsing gateway rather than relying on FormDefinition to
     * have been loaded first. OpenRosa submission processing legitimately uses
     * JavaRosaFormLoader without creating a FormDefinition wrapper.
     */
    static {
        new XFormsModule().registerModule();
    }

    public FormDef loadFormDef(String xmlContent) {
        if (xmlContent == null || xmlContent.isBlank()) {
            throw new FormValidationException("XForm XML content cannot be null or empty");
        }

        // Pre-process XML to handle JavaRosa 4.3.2 limitations:
        // 1. JavaRosa doesn't parse <itext> element, so Localizer is null
        // 2. When form body contains jr:itext() references, parser fails
        // Solution: Strip jr:itext() references from body before parsing
        String processedXml = preprocessXmlForJavaRosa(xmlContent);

        try (Reader reader = new InputStreamReader(
                new ByteArrayInputStream(processedXml.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8)) {
            FormDef formDef;
            synchronized (XFORM_PARSER_LOCK) {
                formDef = new XFormParser(reader).parse();
            }
            // Store original XML for metadata extraction
            FormXmlContentStore.store(formDef, xmlContent);
            log.debug("Successfully parsed XForm: name={}, title={}", formDef.getName(), formDef.getTitle());
            return formDef;
        } catch (XFormParser.ParseException e) {
            log.error("JavaRosa rejected XForm: {}", e.getMessage());
            throw new FormValidationException("Invalid XForm: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new FormValidationException("Could not read XForm XML: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("Failed to parse XForm XML", e);
            throw new FormValidationException("Failed to parse XForm XML: " + safeMessage(e), e);
        }
    }

    /**
     * Parse a submitted instance against its canonical XForm using JavaRosa's
     * two-reader parser. All JavaRosa parsing is serialized through the same
     * application-owned lock used by normal form loading so concurrent OpenRosa
     * submissions cannot trip JavaRosa's global parser lock.
     */
    public FormDef loadFormDef(String formXml, String instanceXml, Map<String, byte[]> externalDatasets) {
        if (formXml == null || formXml.isBlank()) {
            throw new FormValidationException("XForm XML content cannot be null or empty");
        }
        if (instanceXml == null || instanceXml.isBlank()) {
            throw new FormValidationException("Submission instance XML cannot be null or empty");
        }

        String javaRosaCompatibleXml = preprocessXmlForJavaRosa(formXml);
        XFormRuntimePreprocessor.PreparedXForm prepared =
                XFormRuntimePreprocessor.prepare(
                        javaRosaCompatibleXml,
                        externalDatasets == null ? Map.of() : externalDatasets);

        try (Reader formReader = new InputStreamReader(
                        new ByteArrayInputStream(prepared.xml().getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8);
             Reader instanceReader = new InputStreamReader(
                        new ByteArrayInputStream(instanceXml.getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8)) {

            FormDef formDef;
            synchronized (XFORM_PARSER_LOCK) {
                formDef = new XFormParser(formReader, instanceReader).parse();
            }
            if (formDef.getLocalizer() == null) {
                org.javarosa.core.services.locale.Localizer localizer =
                        new org.javarosa.core.services.locale.Localizer();
                localizer.addAvailableLocale("en");
                localizer.setDefaultLocale("en");
                localizer.setLocale("en");
                formDef.setLocalizer(localizer);
            }

            // XFormParser.loadXmlInstance() deliberately restores the saved
            // instance before FormDef initialization. Initialize as an existing
            // instance so triggerables/relevance/calculations are ready for the
            // full FormDef.validate() sweep without preloading new-entry values.
            formDef.initialize(false, new InstanceInitializationFactory());

            FormXmlContentStore.store(formDef, formXml);
            log.debug("Successfully parsed submitted instance for XForm: name={}", formDef.getName());
            return formDef;
        } catch (XFormParser.ParseException e) {
            log.warn("JavaRosa rejected submitted instance: {}", e.getMessage());
            throw new FormValidationException("Invalid submission instance: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new FormValidationException("Could not read submission instance: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.warn("Failed to parse submitted instance with JavaRosa", e);
            throw new FormValidationException("Invalid submission instance: " + safeMessage(e), e);
        }
    }

    /**
     * Compatibility parser for callers that already hold a canonical form XML
     * and a saved/submitted instance. This deliberately mirrors JavaRosa's
     * native two-reader restoration path and does not initialize the returned
     * FormDef again; parse() has already restored the submitted main instance.
     *
     * Package-private so SubmissionXmlParser can preserve its historical
     * tolerant behavior without bypassing the application-owned parse lock.
     */
    static FormDef parseRestoredInstance(String formXml, String instanceXml) {
        if (formXml == null || formXml.isBlank()) {
            throw new FormValidationException("XForm XML content cannot be null or empty");
        }
        if (instanceXml == null || instanceXml.isBlank()) {
            throw new FormValidationException("Submission instance XML cannot be null or empty");
        }

        try (Reader formReader = new StringReader(formXml);
             Reader instanceReader = new StringReader(instanceXml)) {
            synchronized (XFORM_PARSER_LOCK) {
                return new XFormParser(formReader, instanceReader).parse();
            }
        } catch (XFormParser.ParseException e) {
            throw new FormValidationException("Invalid submission instance: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new FormValidationException("Could not read submission instance: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new FormValidationException("Invalid submission instance: " + safeMessage(e), e);
        }
    }

    public FormDef loadFormDef(String formXml, String instanceXml) {
        return loadFormDef(formXml, instanceXml, Map.of());
    }

    public FormDef loadFormDef(InputStream inputStream) {
        if (inputStream == null) {
            throw new FormValidationException("XForm input stream cannot be null");
        }
        try {
            return loadFormDef(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new FormValidationException("Failed to read XForm input stream: " + e.getMessage(), e);
        }
    }

    public String generateFormHash(String xmlContent) {
        if (xmlContent == null || xmlContent.isBlank()) {
            throw new IllegalArgumentException("XML content cannot be null or empty");
        }
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(xmlContent.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                value.append(String.format("%02x", b & 0xff));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm is unavailable", e);
        }
    }

    public String getFormVersion(FormDef formDef) {
        return instanceRootAttribute(formDef, "version");
    }

    public String getFormXmlns(FormDef formDef) {
        Element root = getPrimaryInstanceRoot(formDef);
        if (root == null) {
            return null;
        }
        String namespace = root.getNamespaceURI();
        if (namespace != null && !namespace.isBlank()) {
            return namespace;
        }
        String xmlns = root.getAttribute("xmlns");
        return xmlns == null || xmlns.isBlank() ? null : xmlns;
    }

    public String getInstanceRootNode(FormDef formDef) {
        requireFormDef(formDef);
        FormInstance instance = formDef.getMainInstance();
        TreeElement root = instance == null ? null : instance.getRoot();
        return root == null ? formDef.getName() : root.getName();
    }

    public FormDef loadFormFromXml(String xmlContent) {
        return loadFormDef(xmlContent);
    }

    public FormDef loadFormFromFile(File file) {
        if (file == null) {
            throw new FormValidationException("XForm file cannot be null");
        }
        try (InputStream input = new FileInputStream(file)) {
            return loadFormDef(input);
        } catch (IOException e) {
            throw new FormValidationException("Failed to read XForm file: " + e.getMessage(), e);
        }
    }

    public FormDef loadFormFromMultipartFile(MultipartFile multipartFile) {
        if (multipartFile == null || multipartFile.isEmpty()) {
            throw new FormValidationException("XForm multipart file cannot be null or empty");
        }
        try (InputStream input = multipartFile.getInputStream()) {
            return loadFormDef(input);
        } catch (IOException e) {
            throw new FormValidationException("Failed to read XForm multipart file: " + e.getMessage(), e);
        }
    }

    public String extractFormTitle(FormDef formDef) {
        return getFormTitle(formDef);
    }

    public String extractFormId(FormDef formDef) {
        return getFormId(formDef);
    }

    public String extractVersion(FormDef formDef) {
        return getFormVersion(formDef);
    }

    public String extractInstanceRootNode(FormDef formDef) {
        return getInstanceRootNode(formDef);
    }

    /**
     * Returns the canonical form id from the primary instance root's id attribute.
     * Falls back to FormDef.getName() for non-standard forms.
     */
    public String getFormId(FormDef formDef) {
        requireFormDef(formDef);
        String id = instanceRootAttribute(formDef, "id");
        return id == null || id.isBlank() ? formDef.getName() : id;
    }

    public String getFormTitle(FormDef formDef) {
        requireFormDef(formDef);
        return formDef.getTitle();
    }

    public boolean validateXml(String xmlContent) {
        loadFormDef(xmlContent);
        return true;
    }

    public String getXmlContent(FormDef formDef) {
        requireFormDef(formDef);
        return FormXmlContentStore.get(formDef);
    }

    /**
     * Returns the original attributes declared on an XForm bind for the supplied
     * instance reference. JavaRosa applies known bind expressions to its runtime
     * model but does not expose every original expression through TreeElement.
     * The canonical XML is therefore used for metadata extraction.
     */
    public Map<String, String> getBindAttributes(FormDef formDef, String xpathReference) {
        if (formDef == null || xpathReference == null || xpathReference.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> attributes = extractBindAttributes(formDef)
                .get(normalizeXPathReference(xpathReference));
        return attributes == null ? Collections.emptyMap() : attributes;
    }

    /** Builds a normalized XPath-to-bind-attributes index from the canonical XML. */
    public Map<String, Map<String, String>> extractBindAttributes(FormDef formDef) {
        requireFormDef(formDef);
        String xmlContent = FormXmlContentStore.get(formDef);
        if (xmlContent == null || xmlContent.isBlank()) {
            return Collections.emptyMap();
        }

        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        Element root = parseSecureDocument(xmlContent).getDocumentElement();
        collectBindElements(root, result);
        return result;
    }

    /**
     * FormDef already owns its main instance after parsing. Constructing FormEntryModel
     * performs the normal JavaRosa entry-model setup without inventing a second tree.
     */
    public FormInstance createFormInstance(FormDef formDef) {
        requireFormDef(formDef);
        new FormEntryModel(formDef);
        return formDef.getMainInstance();
    }

    public Map<String, String> extractXPathReferences(FormDef formDef) {
        Map<String, String> xpathRefs = new HashMap<>();
        if (formDef == null || formDef.getMainInstance() == null) {
            return xpathRefs;
        }
        extractXPathReferencesRecursive(formDef.getMainInstance().getRoot(), xpathRefs);
        return xpathRefs;
    }

    private void extractXPathReferencesRecursive(TreeElement element, Map<String, String> xpathRefs) {
        if (element == null) {
            return;
        }
        TreeReference ref = element.getRef();
        if (ref != null && element.getName() != null) {
            xpathRefs.put(element.getName(), ref.toString());
        }
        for (int i = 0; i < element.getNumChildren(); i++) {
            extractXPathReferencesRecursive(element.getChildAt(i), xpathRefs);
        }
    }

    private String instanceRootAttribute(FormDef formDef, String localName) {
        requireFormDef(formDef);
        Element root = getPrimaryInstanceRoot(formDef);
        if (root == null) {
            return null;
        }
        NamedNodeMap attributes = root.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String attributeLocalName = attribute.getLocalName();
            if (localName.equals(attributeLocalName) || localName.equals(attribute.getNodeName())) {
                String value = attribute.getNodeValue();
                return value == null || value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private Element getPrimaryInstanceRoot(FormDef formDef) {
        String xmlContent = FormXmlContentStore.get(formDef);
        if (xmlContent == null || xmlContent.isBlank()) {
            return null;
        }
        Document document = parseSecureDocument(xmlContent);
        Element documentRoot = document.getDocumentElement();
        Element instance = findFirstDescendantByLocalName(documentRoot, "instance");
        return instance == null ? null : firstElementChild(instance);
    }

    private Document parseSecureDocument(String xmlContent) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            setAttribute(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
            setAttribute(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new FormValidationException("Unable to inspect XForm XML metadata: " + safeMessage(e), e);
        }
    }

    private static void setFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (Exception ignored) {
            // The JAXP implementation may not expose every hardening flag. The
            // standard external-access properties above are still applied.
        }
    }


    private static void setAttribute(DocumentBuilderFactory factory, String name, Object value) {
        try {
            factory.setAttribute(name, value);
        } catch (IllegalArgumentException ignored) {
            // Some JAXP providers do not implement these standard properties.
        }
    }

    private static void collectBindElements(Element element,
                                            Map<String, Map<String, String>> result) {
        if (element == null) {
            return;
        }
        String localName = element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
        if ("bind".equals(localName)) {
            String reference = firstNonBlank(element.getAttribute("nodeset"), element.getAttribute("ref"));
            if (reference != null) {
                Map<String, String> attributes = new LinkedHashMap<>();
                NamedNodeMap rawAttributes = element.getAttributes();
                for (int i = 0; i < rawAttributes.getLength(); i++) {
                    Node attribute = rawAttributes.item(i);
                    String name = attribute.getLocalName() == null
                            ? attribute.getNodeName()
                            : attribute.getLocalName();
                    attributes.put(name, attribute.getNodeValue());
                    attributes.putIfAbsent(attribute.getNodeName(), attribute.getNodeValue());
                }
                result.put(normalizeXPathReference(reference), Collections.unmodifiableMap(attributes));
            }
        }
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                collectBindElements((Element) child, result);
            }
        }
    }

    /**
     * Extracts media types from upload elements in the form body.
     * Returns a map of xpath reference -> media type.
     */
    public Map<String, String> extractMediaTypes(FormDef formDef) {
        requireFormDef(formDef);
        String xmlContent = FormXmlContentStore.get(formDef);
        if (xmlContent == null || xmlContent.isBlank()) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new LinkedHashMap<>();
        Element root = parseSecureDocument(xmlContent).getDocumentElement();
        collectUploadElements(root, result);
        return result;
    }

    private static void collectUploadElements(Element element,
                                              Map<String, String> result) {
        if (element == null) {
            return;
        }
        String localName = element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
        if ("upload".equals(localName)) {
            String reference = firstNonBlank(element.getAttribute("ref"), element.getAttribute("nodeset"));
            String mediaType = element.getAttribute("mediatype");
            if (reference != null && mediaType != null && !mediaType.isBlank()) {
                result.put(normalizeXPathReference(reference), mediaType);
            }
        }
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                collectUploadElements((Element) child, result);
            }
        }
    }

    private static String normalizeXPathReference(String reference) {
        if (reference == null) {
            return null;
        }
        return reference.trim()
                .replaceAll("\\[(?:-?\\d+|@template)\\]", "")
                .replaceAll("/{2,}", "/");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Element findFirstDescendantByLocalName(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        String current = parent.getLocalName() == null ? parent.getNodeName() : parent.getLocalName();
        if (localName.equals(current)) {
            return parent;
        }
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                Element found = findFirstDescendantByLocalName((Element) child, localName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Element firstElementChild(Element parent) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                return (Element) child;
            }
        }
        return null;
    }

    private static void requireFormDef(FormDef formDef) {
        if (formDef == null) {
            throw new IllegalArgumentException("FormDef cannot be null");
        }
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    /**
     * Pre-processes XForm XML to work around JavaRosa 4.3.2 limitations:
     * 1. JavaRosa doesn't parse the <itext> element, so Localizer is never populated
     * 2. When form body contains jr:itext() references, parser fails with NPE
     * 
     * This method strips jr:itext() function calls from label, hint, and guidance
     * elements in the form body, replacing them with empty values so JavaRosa can
     * parse the form structure. The original XML is preserved for metadata extraction.
     */
    private String preprocessXmlForJavaRosa(String xmlContent) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            setAttribute(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
            setAttribute(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            Document document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8)));

            Element root = document.getDocumentElement();
            log.debug("Preprocessing XML, root element: {}", root.getNodeName());
            // Find the body element and process it
            Element bodyElement = findFirstDescendantByLocalName(root, "body");
            if (bodyElement != null) {
                log.debug("Found body element, stripping itext references");
                stripItextReferences(bodyElement);
            } else {
                log.warn("No body element found in XForm XML");
            }

            // Serialize back to string
            String result = serializeDocument(document);
            log.debug("Preprocessed XML length: {}", result.length());
            return result;
        } catch (Exception e) {
            log.warn("Failed to preprocess XML for JavaRosa, using original: {}", e.getMessage());
            return xmlContent;
        }
    }

    /**
     * Recursively strips jr:itext() references from label, hint, and guidance elements.
     * For label elements inside item elements (choices), we also strip the ref but
     * provide a placeholder text content so JavaRosa doesn't fail on empty labels.
     * For label elements in form controls (input, select, etc.), we also provide
     * placeholder text so JavaRosa's verifyTextMappings doesn't fail.
     */
    private void stripItextReferences(Element element) {
        if (element == null) {
            return;
        }

        String localName = element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
        
        // Process label, hint, and guidance elements
        if ("label".equals(localName) || "hint".equals(localName) || "guidance".equals(localName)) {
            String ref = element.getAttribute("ref");
            if (ref != null && ref.contains("jr:itext")) {
                // Remove the ref attribute - JavaRosa will use the text content instead
                element.removeAttribute("ref");
                // For label elements inside item (choices), provide a placeholder
                // so JavaRosa doesn't fail on empty labels during verifyTextMappings
                boolean isLabelInItem = "label".equals(localName) && isChildOfItem(element);
                // For label elements in form controls (input, select, select1, etc.),
                // also provide placeholder text so verifyTextMappings doesn't fail
                boolean isLabelInControl = "label".equals(localName) && isChildOfControl(element);
                if (isLabelInItem || isLabelInControl) {
                    // Extract a readable name from the itext reference
                    String placeholder = extractPlaceholderFromItextRef(ref);
                    element.setTextContent(placeholder);
                } else {
                    // Clear any existing text content for other elements (hint, guidance)
                    element.setTextContent("");
                }
            }
        }

        // Recurse into children
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                stripItextReferences((Element) child);
            }
        }
    }

    /**
     * Checks if an element is a descendant of an item element.
     */
    private boolean isChildOfItem(Element element) {
        Node parent = element.getParentNode();
        while (parent != null) {
            if (parent instanceof Element) {
                String parentLocalName = parent.getLocalName() == null ? parent.getNodeName() : parent.getLocalName();
                if ("item".equals(parentLocalName)) {
                    return true;
                }
            }
            parent = parent.getParentNode();
        }
        return false;
    }

    /**
     * Checks if an element is a descendant of a form control element
     * (input, select, select1, upload, output, etc.).
     */
    private boolean isChildOfControl(Element element) {
        Node parent = element.getParentNode();
        while (parent != null) {
            if (parent instanceof Element) {
                String parentLocalName = parent.getLocalName() == null ? parent.getNodeName() : parent.getLocalName();
                if (isFormControl(parentLocalName)) {
                    return true;
                }
            }
            parent = parent.getParentNode();
        }
        return false;
    }

    /**
     * Checks if a local name represents a form control element.
     */
    private boolean isFormControl(String localName) {
        return "input".equals(localName)
                || "select".equals(localName)
                || "select1".equals(localName)
                || "upload".equals(localName)
                || "output".equals(localName)
                || "trigger".equals(localName)
                || "range".equals(localName)
                || "secret".equals(localName)
                || "text".equals(localName)
                || "int".equals(localName)
                || "decimal".equals(localName)
                || "date".equals(localName)
                || "time".equals(localName)
                || "dateTime".equals(localName)
                || "geopoint".equals(localName)
                || "geotrace".equals(localName)
                || "geoshape".equals(localName)
                || "barcode".equals(localName)
                || "binary".equals(localName);
    }

    /**
     * Extracts a readable placeholder from a jr:itext() reference.
     * e.g., jr:itext('/data/gender:option0') -> "option0"
     */
    private String extractPlaceholderFromItextRef(String ref) {
        if (ref == null) {
            return "";
        }
        // Extract the textId from jr:itext('...')
        int start = ref.indexOf("'");
        int end = ref.lastIndexOf("'");
        if (start >= 0 && end > start) {
            String textId = ref.substring(start + 1, end);
            // Get the last part after the last colon or slash
            int lastColon = textId.lastIndexOf(':');
            int lastSlash = textId.lastIndexOf('/');
            int lastSeparator = Math.max(lastColon, lastSlash);
            if (lastSeparator >= 0 && lastSeparator < textId.length() - 1) {
                return textId.substring(lastSeparator + 1);
            }
            return textId;
        }
        return "";
    }

    /**
     * Serializes a DOM Document to a string.
     */
    private String serializeDocument(Document document) {
        try {
            javax.xml.transform.TransformerFactory tf = javax.xml.transform.TransformerFactory.newInstance();
            javax.xml.transform.Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.METHOD, "xml");
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
            
            java.io.StringWriter writer = new java.io.StringWriter();
            transformer.transform(new javax.xml.transform.dom.DOMSource(document), 
                    new javax.xml.transform.stream.StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            log.warn("Failed to serialize document: {}", e.getMessage());
            return "";
        }
    }
}
