package org.odk.platform.service;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares an XForm for the server-side JavaRosa web runtime.
 *
 * <p>The browser runtime cannot rely on JavaRosa's process-global file reference
 * manager for form-version-scoped datasets stored behind the application's
 * StorageService abstraction. This class therefore creates a request-local copy
 * of the XForm, inlines supported active external datasets, and translates the
 * XLSForm/ODK {@code pulldata()} convenience function into an equivalent
 * {@code instance()} lookup that JavaRosa 4.3.2 evaluates natively.</p>
 */
public final class XFormRuntimePreprocessor {

    private static final Pattern FUNCTION_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_.:-])([A-Za-z_][A-Za-z0-9_.:-]*)\\s*\\(");
    private static final Pattern RAW_XPATH_ATTRIBUTE_PATTERN = Pattern.compile(
            "(?:calculate|relevant|constraint|required|readonly|nodeset|ref|value)\\s*=\\s*([\"'])((?:(?!\\1).)*)\\1",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern EXTERNAL_INSTANCE_SRC_PATTERN = Pattern.compile(
            "<(?:(?:[A-Za-z_][\\w.-]*):)?instance\\b[^>]*\\bsrc\\s*=\\s*([\"'])jr://file(?:-csv|-xml)?/.*?\\1",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern EXTERNAL_INSTANCE_FILE_PATTERN = Pattern.compile(
            "<(?:(?:[A-Za-z_][\\w.-]*):)?instance\\b[^>]*\\bsrc\\s*=\\s*([\"'])jr://file(?:-csv|-xml)?/([^\"']+)\\1",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PULLDATA_DATASET_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_.:-])pulldata\\s*\\(\\s*([\"'])([^\"']+)\\1",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> XPATH_EXPRESSION_ATTRIBUTES = Set.of(
            "calculate", "relevant", "constraint", "required", "readonly",
            "nodeset", "ref", "value");

    private static final Set<String> VERIFIED_FUNCTIONS = Set.of(
            "if", "coalesce", "indexed-repeat", "true", "false", "boolean",
            "number", "int", "round", "string", "date", "date-time",
            "decimal-date-time", "decimal-time", "not", "boolean-from-string",
            "format-date", "format-date-time", "abs", "acos", "asin", "atan",
            "atan2", "cos", "exp", "exp10", "log", "log10", "pi", "sin",
            "sqrt", "tan", "selected", "is-selected", "count-selected",
            "selected-at", "position", "count", "count-non-empty", "sum", "max",
            "min", "today", "now", "concat", "join", "substr",
            "substring-before", "substring-after", "translate", "contains",
            "starts-with", "ends-with", "string-length", "normalize-space",
            "checklist", "weighted-checklist", "regex", "depend", "random", "once",
            "uuid", "version", "property", "pow", "enclosed-area", "area", "distance",
            "digest", "randomize", "base64-decode", "extract-signed", "instance",
            "current", "jr:choice-name", "jr:itext", "text", "node", "last");

    private XFormRuntimePreprocessor() {
    }

    public record PreparedXForm(String xml, List<String> warnings) {
        public PreparedXForm {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Return logical external dataset names referenced by an XForm.
     *
     * <p>This lightweight discovery pass is intentionally tolerant of legacy XForm
     * XML. It is used before JavaRosa parsing so project-scoped Entity Lists can be
     * materialized into the same request-local dataset map as uploaded attachments.</p>
     */
    public static Set<String> referencedExternalDatasetNames(String xformXml) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (xformXml == null || xformXml.isBlank()) {
            return names;
        }

        Matcher instanceMatcher = EXTERNAL_INSTANCE_FILE_PATTERN.matcher(xformXml);
        while (instanceMatcher.find()) {
            String fileName = sourceFileName(instanceMatcher.group(2));
            if (!fileName.isBlank()) {
                names.add(stripExtension(fileName));
            }
        }

        Matcher pulldataMatcher = PULLDATA_DATASET_PATTERN.matcher(xformXml);
        while (pulldataMatcher.find()) {
            String name = pulldataMatcher.group(2).trim();
            if (!name.isBlank()) {
                names.add(stripExtension(sourceFileName(name)));
            }
        }
        return names;
    }

    public static PreparedXForm prepare(String xformXml, Map<String, byte[]> externalDatasets) {
        if (xformXml == null || xformXml.isBlank()) {
            throw new IllegalArgumentException("XForm XML cannot be blank");
        }

        // Preserve JavaRosa's tolerance for legacy/non-strict XForm source when
        // no external-data rewrite is needed. Some deployed forms contain XPath
        // source that a strict JAXP DOM parser rejects even though JavaRosa accepts it.
        boolean needsDomRewrite = xformXml.contains("pulldata(")
                || EXTERNAL_INSTANCE_SRC_PATTERN.matcher(xformXml).find();
        if (!needsDomRewrite) {
            LinkedHashSet<String> warnings = new LinkedHashSet<>();
            scanUnsupportedFunctionsRaw(xformXml, warnings);
            return new PreparedXForm(xformXml, new ArrayList<>(warnings));
        }

        try {
            Document document = parseXml(xformXml);
            LinkedHashSet<String> warnings = new LinkedHashSet<>();
            LinkedHashSet<String> pulldataDatasetIds = new LinkedHashSet<>();
            boolean changed = transformExpressions(document, pulldataDatasetIds, warnings);

            Element model = firstElement(document, "model");
            if (model == null) {
                warnings.add("XForm does not contain an XForms model element; external-data logic cannot be prepared.");
                return new PreparedXForm(xformXml, new ArrayList<>(warnings));
            }

            for (String datasetId : pulldataDatasetIds) {
                if (findSecondaryInstance(model, datasetId) == null) {
                    addExternalInstanceDeclaration(document, model, datasetId);
                    changed = true;
                }
            }

            Map<String, byte[]> aliases = buildDatasetAliases(externalDatasets);
            List<Element> secondaryInstances = secondaryInstances(model);
            for (Element instance : secondaryInstances) {
                String instanceId = instance.getAttribute("id").trim();
                String src = instance.getAttribute("src").trim();
                if (src.isEmpty()) {
                    continue;
                }

                byte[] content = resolveDataset(aliases, instanceId, src);
                if (content == null) {
                    warnings.add("External dataset '" + datasetDisplayName(instanceId, src)
                            + "' is referenced by the form but is not uploaded, active, or available as a project Entity List.");
                    replaceInstanceContent(instance, document.createElement("root"));
                    changed = true;
                    continue;
                }

                if (isCsvSource(src, instanceId)) {
                    Element root = csvToInstanceRoot(document, content, instanceId);
                    replaceInstanceContent(instance, root);
                    changed = true;
                } else if (isXmlSource(src, instanceId)) {
                    Document external = parseXml(new String(content, StandardCharsets.UTF_8));
                    replaceInstanceContent(instance, document.importNode(external.getDocumentElement(), true));
                    changed = true;
                } else {
                    warnings.add("External dataset '" + datasetDisplayName(instanceId, src)
                            + "' uses a format that the web runtime does not yet support. CSV and XML are supported.");
                    replaceInstanceContent(instance, document.createElement("root"));
                    changed = true;
                }
            }

            scanUnsupportedFunctions(document, warnings);
            return new PreparedXForm(changed ? serialize(document) : xformXml, new ArrayList<>(warnings));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to prepare XForm runtime data", e);
        }
    }

    private static boolean transformExpressions(Document document,
                                                Set<String> pulldataDatasetIds,
                                                Set<String> warnings) {
        boolean changed = false;
        NodeList all = document.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            NamedNodeMap attributes = element.getAttributes();
            for (int j = 0; j < attributes.getLength(); j++) {
                Node attribute = attributes.item(j);
                String localName = attribute.getLocalName() != null ? attribute.getLocalName() : attribute.getNodeName();
                if (!XPATH_EXPRESSION_ATTRIBUTES.contains(localName)) {
                    continue;
                }
                String expression = attribute.getNodeValue();
                PulldataTransform transformed = transformPulldata(expression, pulldataDatasetIds, warnings);
                if (!transformed.expression().equals(expression)) {
                    attribute.setNodeValue(transformed.expression());
                    changed = true;
                }
            }
        }
        return changed;
    }

    private record PulldataTransform(String expression) {
    }

    private static PulldataTransform transformPulldata(String expression,
                                                       Set<String> datasetIds,
                                                       Set<String> warnings) {
        if (expression == null || expression.indexOf("pulldata") < 0) {
            return new PulldataTransform(expression);
        }

        StringBuilder result = new StringBuilder();
        int cursor = 0;
        while (cursor < expression.length()) {
            int start = findFunctionStart(expression, "pulldata", cursor);
            if (start < 0) {
                result.append(expression, cursor, expression.length());
                break;
            }
            result.append(expression, cursor, start);

            int open = skipWhitespace(expression, start + "pulldata".length());
            if (open >= expression.length() || expression.charAt(open) != '(') {
                result.append("pulldata");
                cursor = start + "pulldata".length();
                continue;
            }
            int close = findMatchingParen(expression, open);
            if (close < 0) {
                warnings.add("Unable to parse pulldata() expression: " + expression);
                result.append(expression.substring(start));
                break;
            }

            String invocation = expression.substring(start, close + 1);
            List<String> args = splitTopLevelArguments(expression.substring(open + 1, close));
            if (args.size() != 4) {
                warnings.add("Unsupported pulldata() signature (expected four arguments): " + invocation);
                result.append(invocation);
                cursor = close + 1;
                continue;
            }

            String datasetId = quotedLiteral(args.get(0));
            String returnColumn = quotedLiteral(args.get(1));
            String lookupColumn = quotedLiteral(args.get(2));
            if (datasetId == null || !isSafeNodeName(returnColumn) || !isSafeNodeName(lookupColumn)) {
                warnings.add("Unsupported pulldata() dataset/column name: " + invocation);
                result.append(invocation);
                cursor = close + 1;
                continue;
            }

            PulldataTransform lookup = transformPulldata(args.get(3).trim(), datasetIds, warnings);
            datasetIds.add(datasetId);
            result.append("instance(").append(xpathStringLiteral(datasetId)).append(")")
                    .append("/root/item[").append(lookupColumn).append(" = ")
                    .append(lookup.expression()).append("]/").append(returnColumn);
            cursor = close + 1;
        }

        return new PulldataTransform(result.toString());
    }

    private static int findFunctionStart(String expression, String functionName, int fromIndex) {
        int index = expression.indexOf(functionName, fromIndex);
        while (index >= 0) {
            boolean validPrefix = index == 0 || !isFunctionNameChar(expression.charAt(index - 1));
            int after = index + functionName.length();
            int open = skipWhitespace(expression, after);
            if (validPrefix && open < expression.length() && expression.charAt(open) == '(') {
                return index;
            }
            index = expression.indexOf(functionName, index + 1);
        }
        return -1;
    }

    private static boolean isFunctionNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ':';
    }

    private static int skipWhitespace(String value, int index) {
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int findMatchingParen(String value, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitTopLevelArguments(String args) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(args.substring(start, i).trim());
                start = i + 1;
            }
        }
        parts.add(args.substring(start).trim());
        return parts;
    }

    private static String quotedLiteral(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() < 2) {
            return null;
        }
        char quote = trimmed.charAt(0);
        if ((quote != '\'' && quote != '"') || trimmed.charAt(trimmed.length() - 1) != quote) {
            return null;
        }
        return trimmed.substring(1, trimmed.length() - 1);
    }

    private static String xpathStringLiteral(String value) {
        if (!value.contains("'")) {
            return "'" + value + "'";
        }
        if (!value.contains("\"")) {
            return "\"" + value + "\"";
        }
        throw new IllegalArgumentException("Dataset id cannot contain both quote characters: " + value);
    }

    private static boolean isSafeNodeName(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_.-]*");
    }

    private static void addExternalInstanceDeclaration(Document document, Element model, String datasetId) {
        String namespace = model.getNamespaceURI();
        String qualifiedName = model.getPrefix() == null ? "instance" : model.getPrefix() + ":instance";
        Element instance = namespace == null
                ? document.createElement("instance")
                : document.createElementNS(namespace, qualifiedName);
        instance.setAttribute("id", datasetId);
        instance.setAttribute("src", "jr://file-csv/" + datasetId + (datasetId.toLowerCase(Locale.ROOT).endsWith(".csv") ? "" : ".csv"));

        Node insertBefore = null;
        NodeList children = model.getChildNodes();
        boolean seenInstance = false;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            if ("instance".equals(child.getLocalName()) || "instance".equals(child.getNodeName())) {
                seenInstance = true;
                continue;
            }
            if (seenInstance) {
                insertBefore = child;
                break;
            }
        }
        if (insertBefore != null) {
            model.insertBefore(instance, insertBefore);
        } else {
            model.appendChild(instance);
        }
    }

    private static Element findSecondaryInstance(Element model, String id) {
        for (Element instance : secondaryInstances(model)) {
            if (id.equals(instance.getAttribute("id"))) {
                return instance;
            }
        }
        return null;
    }

    private static List<Element> secondaryInstances(Element model) {
        List<Element> result = new ArrayList<>();
        NodeList children = model.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) child;
            String local = element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
            if ("instance".equals(local) && element.hasAttribute("id")) {
                result.add(element);
            }
        }
        return result;
    }

    private static Map<String, byte[]> buildDatasetAliases(Map<String, byte[]> datasets) {
        Map<String, byte[]> aliases = new HashMap<>();
        if (datasets == null) {
            return aliases;
        }
        datasets.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            String normalized = key.replace('\\', '/');
            String basename = normalized.substring(normalized.lastIndexOf('/') + 1);
            aliases.putIfAbsent(key, value);
            aliases.putIfAbsent(normalized, value);
            aliases.putIfAbsent(basename, value);
            aliases.putIfAbsent(stripExtension(basename), value);
        });
        return aliases;
    }

    private static byte[] resolveDataset(Map<String, byte[]> aliases, String instanceId, String src) {
        String sourceName = sourceFileName(src);
        for (String candidate : List.of(src, sourceName, stripExtension(sourceName), instanceId)) {
            byte[] content = aliases.get(candidate);
            if (content != null) {
                return content;
            }
        }
        return null;
    }

    private static String datasetDisplayName(String instanceId, String src) {
        String name = sourceFileName(src);
        return !name.isBlank() ? name : instanceId;
    }

    private static String sourceFileName(String src) {
        if (src == null) {
            return "";
        }
        String normalized = src.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String stripExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private static boolean isCsvSource(String src, String id) {
        String lower = (src + " " + id).toLowerCase(Locale.ROOT);
        return lower.contains("file-csv") || lower.endsWith(".csv") || lower.contains(".csv ");
    }

    private static boolean isXmlSource(String src, String id) {
        String lower = (src + " " + id).toLowerCase(Locale.ROOT);
        return lower.endsWith(".xml") || lower.contains(".xml ") ||
                (!lower.contains(".json") && !lower.contains(".geojson") &&
                        !lower.contains(".xlsx") && !lower.contains(".xls"));
    }

    private static Element csvToInstanceRoot(Document document, byte[] bytes, String datasetId) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        List<List<String>> rows = parseCsv(text);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("External CSV dataset '" + datasetId + "' is empty");
        }
        List<String> headers = rows.get(0);
        if (headers.isEmpty()) {
            throw new IllegalArgumentException("External CSV dataset '" + datasetId + "' has no columns");
        }
        for (String header : headers) {
            if (!isSafeNodeName(header)) {
                throw new IllegalArgumentException("External CSV dataset '" + datasetId
                        + "' contains an invalid column name: " + header);
            }
        }

        Element root = document.createElement("root");
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> values = rows.get(rowIndex);
            if (values.size() == 1 && values.get(0).isBlank()) {
                continue;
            }
            Element item = document.createElement("item");
            for (int column = 0; column < headers.size(); column++) {
                Element field = document.createElement(headers.get(column));
                String value = column < values.size() ? values.get(column) : "";
                field.setTextContent(value);
                item.appendChild(field);
            }
            root.appendChild(item);
        }
        return root;
    }

    private static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("CSV contains an unterminated quoted field");
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private static void replaceInstanceContent(Element instance, Node dataRoot) {
        while (instance.hasChildNodes()) {
            instance.removeChild(instance.getFirstChild());
        }
        instance.removeAttribute("src");
        instance.appendChild(dataRoot);
    }

    private static void scanUnsupportedFunctionsRaw(String xformXml, Set<String> warnings) {
        Matcher attributeMatcher = RAW_XPATH_ATTRIBUTE_PATTERN.matcher(xformXml);
        Set<String> warned = new HashSet<>();
        while (attributeMatcher.find()) {
            Matcher functionMatcher = FUNCTION_PATTERN.matcher(attributeMatcher.group(2));
            while (functionMatcher.find()) {
                String function = functionMatcher.group(1);
                if (!VERIFIED_FUNCTIONS.contains(function) && warned.add(function)) {
                    warnings.add("XForm function '" + function
                            + "()' is not verified by the web runtime and may require ODK Collect or additional server support.");
                }
            }
        }
    }

    private static void scanUnsupportedFunctions(Document document, Set<String> warnings) {
        NodeList all = document.getElementsByTagName("*");
        Set<String> warned = new HashSet<>();
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            NamedNodeMap attributes = element.getAttributes();
            for (int j = 0; j < attributes.getLength(); j++) {
                Node attribute = attributes.item(j);
                String localName = attribute.getLocalName() != null ? attribute.getLocalName() : attribute.getNodeName();
                if (!XPATH_EXPRESSION_ATTRIBUTES.contains(localName)) {
                    continue;
                }
                Matcher matcher = FUNCTION_PATTERN.matcher(attribute.getNodeValue());
                while (matcher.find()) {
                    String function = matcher.group(1);
                    if (!VERIFIED_FUNCTIONS.contains(function) && warned.add(function)) {
                        warnings.add("XForm function '" + function
                                + "()' is not verified by the web runtime and may require ODK Collect or additional server support.");
                    }
                }
            }
        }
    }

    private static Element firstElement(Document document, String localName) {
        NodeList all = document.getElementsByTagNameNS("*", localName);
        if (all.getLength() > 0) {
            return (Element) all.item(0);
        }
        NodeList fallback = document.getElementsByTagName(localName);
        return fallback.getLength() > 0 ? (Element) fallback.item(0) : null;
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (IllegalArgumentException ignored) {
            // Some XML providers do not expose these JAXP properties.
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static String serialize(Document document) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        } catch (IllegalArgumentException ignored) {
            // Some TransformerFactory implementations do not expose these JAXP properties.
        }
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }
}
