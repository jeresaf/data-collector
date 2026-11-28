package org.odk.platform.service;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Advisory SAME-PATH static XForm screening only. It is NOT full JavaRosa
 * evaluation and NEVER grants access to historical XML answers or draft writes.
 *
 * Conservative 3B-4B-2B-2A subset: static scalar input controls only; any
 * repeat, dynamic calculation, output expression or external instance blocks.
 * Disagreement or unsupported constructs fail closed.
 */
public final class StaticTypedMappingFieldPolicy {
    private StaticTypedMappingFieldPolicy() { }

    private static final String XF = "http://www.w3.org/2002/xforms";
    private static final Pattern PATH = Pattern.compile(
        "/[A-Za-z_][A-Za-z0-9_.-]*(/[A-Za-z_][A-Za-z0-9_.-]*){1,11}");
    private static final Set<String> DYNAMIC = Set.of(
        "repeat", "itemset", "setvalue", "setfocus", "action", "output",
        "submission", "load", "upload", "trigger", "select", "select1");
    private static final Set<String> UNSAFE_BIND = Set.of(
        "calculate", "relevant", "constraint", "required", "readonly",
        "preload", "preloadParams", "count");

    public record Finding(String targetPath, String sourceType,
                          String targetType, String state) {
        public Finding {
            if (targetPath == null || state == null) {
                throw new IllegalArgumentException("Typed finding is incomplete");
            }
        }
    }

    /**
     * Source/target XForms are supplied only by the caller's trusted
     * persisted form_versions rows, NEVER from a browser request.
     */
    public static List<Finding> inspect(
            String oldXForm, String targetXForm, List<String> paths) {
        if (paths == null || paths.isEmpty() || paths.size() > 30 ||
                paths.stream().anyMatch(p ->
                    p == null || p.length() > 255 || !PATH.matcher(p).matches()) ||
                paths.stream().distinct().count() != paths.size()) {
            throw new IllegalArgumentException("Bounded absolute target paths required");
        }
        Model oldModel = model(oldXForm);
        Model newModel = model(targetXForm);
        List<Finding> result = new ArrayList<>();
        for (String path : paths) {
            String sourceType = oldModel.type(path);
            String targetType = newModel.type(path);
            String state;
            if (isMetadata(path)) {
                state = "BLOCKED_METADATA";
            } else if (!oldModel.safe() || !newModel.safe()) {
                state = "BLOCKED_DYNAMIC_OR_EXTERNAL_FORM";
            } else if (sourceType == null || targetType == null) {
                state = "BLOCKED_MISSING_OR_AMBIGUOUS_FIELD";
            } else if (!sourceType.equals(targetType)) {
                state = "BLOCKED_TYPE_CHANGE";
            } else if (!Set.of("TEXT", "INTEGER", "DECIMAL", "DATE")
                    .contains(sourceType)) {
                state = "BLOCKED_UNSUPPORTED_TYPE";
            } else {
                state = "STATIC_SAME_PATH_TYPE_CANDIDATE_ONLY";
            }
            result.add(new Finding(path, sourceType, targetType, state));
        }
        return List.copyOf(result);
    }

    /** Independent, conservative preflight for explicit one-to-one proposals. */
    public record ExplicitFinding(ExplicitMappingManifest.FieldRule rule, String state) { }

    public static List<ExplicitFinding> inspectExplicit(
            String sourceXml, String targetXml, ExplicitMappingManifest manifest) {
        if (manifest == null) throw new IllegalArgumentException("Mapping manifest required");
        Model old = model(sourceXml);
        Model next = model(targetXml);
        // A rename may map only a removed leaf to a genuinely new leaf;
        // ambiguity anywhere in either inventory blocks the entire proposal.
        Set<String> oldPaths = explicitInventory(old);
        Set<String> newPaths = explicitInventory(next);
        boolean identity = !old.data().getAttribute("id").isBlank() &&
                old.data().getAttribute("id").equals(next.data().getAttribute("id")) &&
                !old.data().getAttribute("version").isBlank() &&
                !next.data().getAttribute("version").isBlank();
        List<ExplicitFinding> findings = new ArrayList<>();
        for (var rule : manifest.rules()) {
            String state;
            if (!identity || !old.safe() || !next.safe()) {
                state = "BLOCKED_DYNAMIC_OR_EXTERNAL_FORM";
            } else if (isMetadata(rule.sourcePath()) || isMetadata(rule.targetPath())) {
                state = "BLOCKED_METADATA";
            } else if (!oldPaths.contains(rule.sourcePath()) || !newPaths.contains(rule.targetPath())) {
                state = "BLOCKED_UNKNOWN_PATH";
            } else if (!rule.sourcePath().equals(rule.targetPath()) &&
                    (newPaths.contains(rule.sourcePath()) || oldPaths.contains(rule.targetPath()))) {
                state = "BLOCKED_RENAME_COLLISION";
            } else if (!rule.type().name().equals(old.type(rule.sourcePath())) ||
                    !rule.type().name().equals(next.type(rule.targetPath()))) {
                state = "BLOCKED_TYPE_OR_UNSUPPORTED";
            } else {
                state = "STATIC_EXPLICIT_CANDIDATE_ONLY";
            }
            findings.add(new ExplicitFinding(rule, state));
        }
        return List.copyOf(findings);
    }

    private static Set<String> explicitInventory(Model model) {
        Set<String> paths = new java.util.HashSet<>();
        Set<String> all = new java.util.HashSet<>();
        record NodePath(Element element, String path, int depth) { }
        var pending = new java.util.ArrayDeque<NodePath>();
        pending.add(new NodePath(model.data(), "/" + model.data().getLocalName(), 1));
        while (!pending.isEmpty()) {
            var item = pending.removeLast();
            if (item.depth() > 80 || all.size() >= 48000 || !all.add(item.path()))
                throw new IllegalArgumentException("Ambiguous or oversized primary instance");
            if (!hasElementChild(item.element())) paths.add(item.path());
            for (Node n = item.element().getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e)
                    pending.add(new NodePath(e, item.path() + "/" + e.getLocalName(), item.depth() + 1));
            }
        }
        // Ref and nodeset are mutually exclusive; do not silently drop
        // unsupported references or duplicate definitions.
        for (String kind : List.of("bind", "input", "group")) {
            Set<String> seen = new java.util.HashSet<>();
            NodeList nodes = model.document().getElementsByTagNameNS(XF, kind);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element e = (Element) nodes.item(i);
                String ref = e.getAttribute("ref"), nodeset = e.getAttribute("nodeset");
                if ("group".equals(kind) && ref.isEmpty() && nodeset.isEmpty() && !e.hasAttribute("bind"))
                    continue;
                String path = ref.isEmpty() ? nodeset : ref;
                if ((!ref.isEmpty() && !nodeset.isEmpty()) || e.hasAttribute("bind") ||
                        !all.contains(path) || !path.matches("/[A-Za-z_][A-Za-z0-9_.-]*(/[A-Za-z_][A-Za-z0-9_.-]*){0,79}") ||
                        !seen.add(path))
                    throw new IllegalArgumentException("Ambiguous or unsupported form reference");
            }
        }
        return paths;
    }

    private static boolean isMetadata(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("/meta/") ||
               lower.endsWith("/instanceid") ||
               lower.endsWith("/deprecatedid") ||
               lower.endsWith("/deviceid");
    }

    private record Model(Element data, Element model, Document document,
                         boolean safe) {
        String type(String path) {
            String[] parts = path.substring(1).split("/");
            if (data == null || !parts[0].equals(data.getLocalName())) return null;
            Element cursor = data;
            for (int i = 1; i < parts.length; i++) {
                cursor = uniqueChild(cursor, parts[i]);
                if (cursor == null) return null;
            }
            if (hasElementChild(cursor)) return null;
            int controls = 0;
            NodeList inputs = document.getElementsByTagNameNS(XF, "input");
            for (int i = 0; i < inputs.getLength(); i++) {
                Element input = (Element) inputs.item(i);
                if (path.equals(input.getAttribute("ref"))) {
                    if (unsafeAttributes(input)) return null;
                    controls++;
                }
            }
            if (controls != 1) return null;

            NodeList binds = model.getElementsByTagNameNS(XF, "bind");
            int count = 0;
            String resolved = "TEXT"; // XForms default binding type is string.
            for (int i = 0; i < binds.getLength(); i++) {
                Element bind = (Element) binds.item(i);
                String ref = bind.hasAttribute("nodeset") ?
                    bind.getAttribute("nodeset") : bind.getAttribute("ref");
                if (!path.equals(ref)) continue;
                if (unsafeAttributes(bind)) return null;
                count++;
                resolved = switch (bind.getAttribute("type").trim()) {
                    case "", "string", "xsd:string" -> "TEXT";
                    case "int", "integer", "xsd:int", "xsd:integer" -> "INTEGER";
                    case "decimal", "xsd:decimal" -> "DECIMAL";
                    case "date", "xsd:date" -> "DATE";
                    default -> "UNSUPPORTED";
                };
            }
            return count > 1 ? null : resolved;
        }
    }

    private static Model model(String xml) {
        if (xml == null || xml.isBlank() || xml.length() > 8_388_608) {
            throw new IllegalArgumentException("Form is missing or too large");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "100");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/elementAttributeLimit", "100");
            Document doc = factory.newDocumentBuilder().parse(
                    new InputSource(new StringReader(xml)));
            NodeList models = doc.getElementsByTagNameNS(XF, "model");
            if (models.getLength() != 1) {
                throw new IllegalArgumentException("Exactly one XForms model required");
            }
            Element xModel = (Element) models.item(0);
            NodeList instances = xModel.getElementsByTagNameNS(XF, "instance");
            if (instances.getLength() != 1) {
                throw new IllegalArgumentException("External/secondary instances blocked");
            }
            Element instance = (Element) instances.item(0);
            Element data = firstChild(instance);
            if (data == null || firstChild(data) == null ||
                    instance.hasAttribute("src") ||
                    java.util.stream.IntStream.range(0, instance.getChildNodes().getLength())
                        .filter(i -> instance.getChildNodes().item(i) instanceof Element).count() != 1) {
                throw new IllegalArgumentException("Non-inline instance blocked");
            }
            boolean safe = true;
            for (String type : DYNAMIC) {
                if (doc.getElementsByTagNameNS(XF, type).getLength() != 0) {
                    safe = false;
                }
            }
            // No external XPath/helper namespace or dynamic model action
            // can be silently treated as a plain same-path scalar.
            // Group/control-level XForms restrictions may apply even when
            // no <bind> exists for an otherwise simple scalar input.
            // Inspect every XForms element, not only direct bind nodes.
            NodeList elements = doc.getElementsByTagNameNS(XF, "*");
            for (int i = 0; i < elements.getLength(); i++) {
                if (unsafeAttributes((Element) elements.item(i))) safe = false;
            }
            return new Model(data, xModel, doc, safe);
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Exact persisted XForm failed secure static inspection", exception);
        }
    }

    private static boolean unsafeAttributes(Element element) {
        var attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            String local = attr.getLocalName() == null ?
                    attr.getNodeName() : attr.getLocalName();
            if (UNSAFE_BIND.contains(local)) return true;
        }
        return false;
    }

    private static Element firstChild(Element parent) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) return e;
        }
        return null;
    }

    private static boolean hasElementChild(Element parent) {
        return firstChild(parent) != null;
    }

    private static Element uniqueChild(Element parent, String name) {
        Element found = null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getLocalName())) {
                if (found != null) return null;
                found = e;
            }
        }
        return found;
    }
}
