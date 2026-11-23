package org.odk.platform.service;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Read-only, fail-closed comparison of simple absolute XForm bind/repeat paths.
 *
 * This is a review aid, NOT an answer-migration engine or mapping grant.
 * Renames are intentionally represented as REMOVED + ADDED; comparing names
 * must never authorize transfer of historical values to another field.
 * Dynamic/relative XPath and missing schemas produce an UNKNOWN status.
 */
public final class FormVersionSchemaDiff {
    private static final String XFORMS_NS = "http://www.w3.org/2002/xforms";
    private static final String SIMPLE_ABSOLUTE_PATH =
            "/[A-Za-z_][A-Za-z0-9_.-]*(/[A-Za-z_][A-Za-z0-9_.-]*)+";

    private FormVersionSchemaDiff() { }

    public record Change(String path, String kind, String sourceType, String targetType) { }

    public record Report(String status, boolean mappingApprovalRequired,
                         List<Change> changes, List<String> warnings) { }

    private record Schema(Map<String, String> fields, Set<String> repeats,
                          List<String> warnings) { }

    public static Report compare(String sourceXFormXml, String targetXFormXml) {
        if (sourceXFormXml == null || sourceXFormXml.isBlank()) {
            return new Report("SOURCE_SCHEMA_UNAVAILABLE", true, List.of(),
                    List.of("Historical XForm definition unavailable; do not infer from target"));
        }
        if (targetXFormXml == null || targetXFormXml.isBlank()) {
            return new Report("TARGET_SCHEMA_UNAVAILABLE", true, List.of(),
                    List.of("Target XForm definition unavailable"));
        }

        Schema source = readSchema(sourceXFormXml, "source");
        Schema target = readSchema(targetXFormXml, "target");
        List<String> warnings = new ArrayList<>(source.warnings());
        warnings.addAll(target.warnings());
        List<Change> changes = new ArrayList<>();

        TreeSet<String> paths = new TreeSet<>(source.fields().keySet());
        paths.addAll(target.fields().keySet());
        for (String path : paths) {
            String oldType = source.fields().get(path);
            String newType = target.fields().get(path);
            if (oldType == null) {
                changes.add(new Change(path, "ADDED", null, newType));
            } else if (newType == null) {
                changes.add(new Change(path, "REMOVED", oldType, null));
            } else if (!oldType.equals(newType)) {
                changes.add(new Change(path, "TYPE_CHANGED", oldType, newType));
            }
        }

        TreeSet<String> repeatPaths = new TreeSet<>(source.repeats());
        repeatPaths.addAll(target.repeats());
        for (String path : repeatPaths) {
            if (!source.repeats().contains(path)) {
                changes.add(new Change(path, "REPEAT_ADDED", null, null));
            } else if (!target.repeats().contains(path)) {
                changes.add(new Change(path, "REPEAT_REMOVED", null, null));
            }
        }

        // Even identical bind sets do NOT imply that calculate, relevant,
        // constraint, choices, label or external-data semantics are unchanged.
        // These findings are never a positive authorization to move answers.
        String status = !warnings.isEmpty()
                ? "UNKNOWN_REVIEW_REQUIRED"
                : changes.isEmpty()
                    ? "NO_BIND_DELTA_REVIEW_REQUIRED"
                    : "BIND_DELTA_REVIEW_REQUIRED";
        return new Report(status, true, List.copyOf(changes), List.copyOf(warnings));
    }

    private static Schema readSchema(String xml, String context) {
        Map<String, String> fields = new TreeMap<>();
        Set<String> repeats = new TreeSet<>();
        List<String> warnings = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            NodeList binds = document.getElementsByTagNameNS(XFORMS_NS, "bind");
            if (binds.getLength() == 0) {
                warnings.add(context + ": no explicit XForms binds; field inventory incomplete");
            }
            for (int i = 0; i < binds.getLength(); i++) {
                Element bind = (Element) binds.item(i);
                String path = reference(bind);
                if (!supportedPath(path)) {
                    warnings.add(context + ": unsupported or relative bind path");
                    continue;
                }
                String type = bind.getAttribute("type").trim();
                if (type.isEmpty()) {
                    warnings.add(context + ": bind type unspecified for " + path);
                    continue;
                }
                if (fields.putIfAbsent(path, type) != null) {
                    warnings.add(context + ": duplicate bind for " + path);
                }
            }
            NodeList repeatNodes = document.getElementsByTagNameNS(XFORMS_NS, "repeat");
            for (int i = 0; i < repeatNodes.getLength(); i++) {
                String path = reference((Element) repeatNodes.item(i));
                if (!supportedPath(path)) {
                    warnings.add(context + ": unsupported or relative repeat path");
                } else if (!repeats.add(path)) {
                    warnings.add(context + ": duplicate repeat path " + path);
                }
            }
        } catch (Exception e) {
            // Parsing errors must never be converted into a "no changes" result,
            // nor return original form XML, field values, or parser exception text.
            warnings.add(context + ": XForm schema could not be safely inspected");
            fields.clear();
            repeats.clear();
        }
        return new Schema(fields, repeats, warnings);
    }

    private static String reference(Element element) {
        String nodeset = element.getAttribute("nodeset").trim();
        return nodeset.isEmpty() ? element.getAttribute("ref").trim() : nodeset;
    }

    private static boolean supportedPath(String path) {
        return path != null && path.matches(SIMPLE_ABSOLUTE_PATH);
    }
}
