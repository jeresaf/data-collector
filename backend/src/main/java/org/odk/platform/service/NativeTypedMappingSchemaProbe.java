package org.odk.platform.service;

import org.javarosa.core.model.Constants;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.instance.TreeElement;

import java.util.ArrayList;
import java.util.List;

/**
 * M3D4B2B2B: independent JavaRosa 4.3.2 schema/type agreement proof.
 *
 * This object never receives the historical answer XML, never returns field
 * values, and cannot authorize a transfer. The existing static XXE-safe
 * XForm policy always runs FIRST. JavaRosa runs on fresh, separate FormDefs
 * to confirm actual instance-tree data types instead of trusting type labels
 * supplied by clients or an XML-only analysis.
 *
 * JavaRosa type agreement is NOT semantic equivalence, operator consent,
 * full target value validation, a signed grant or submit authorization.
 */
public final class NativeTypedMappingSchemaProbe {
    private NativeTypedMappingSchemaProbe() { }

    public record Evidence(
            String targetPath, String staticState,
            String sourceNativeType, String targetNativeType, String state,
            boolean canReadHistoricalAnswers, boolean canTransfer,
            boolean canFinalize, boolean canSubmit) {
        public Evidence {
            if (targetPath == null || staticState == null || state == null ||
                    canReadHistoricalAnswers || canTransfer || canFinalize || canSubmit) {
                throw new IllegalArgumentException(
                    "Native schema evidence cannot authorize historical transfer");
            }
        }
    }

    public static List<Evidence> inspect(
            String sourceXForm, String targetXForm, List<String> paths,
            JavaRosaFormLoader javaRosa) {
        if (javaRosa == null) {
            throw new IllegalArgumentException("Independent JavaRosa parser required");
        }

        // This secure static gate validates bounded absolute paths, one inline
        // instance, no repeats/dynamic controls, and fail-closed controls/binds.
        List<StaticTypedMappingFieldPolicy.Finding> staticFindings =
                StaticTypedMappingFieldPolicy.inspect(
                    sourceXForm, targetXForm, paths);

        boolean hasStaticCandidates = staticFindings.stream().anyMatch(f ->
                "STATIC_SAME_PATH_TYPE_CANDIDATE_ONLY".equals(f.state()));
        if (!hasStaticCandidates) {
            return staticFindings.stream().map(f -> new Evidence(
                f.targetPath(), f.state(), null, null,
                "BLOCKED_STATIC_POLICY", false, false, false, false
            )).toList();
        }

        // One parsed FormDef per exact archived version. The loader already
        // serializes access to the JavaRosa parser global lock. No shared
        // mutable FormDef, caches containing private answers or extra dataset.
        FormDef historic;
        FormDef target;
        try {
            historic = javaRosa.loadFormDef(sourceXForm);
            target = javaRosa.loadFormDef(targetXForm);
        } catch (RuntimeException parseFailure) {
            // No XML or exception message (may embed form content) is returned.
            return staticFindings.stream().map(f -> new Evidence(
                f.targetPath(), f.state(), null, null,
                "BLOCKED_NATIVE_FORM_PARSE", false, false, false, false
            )).toList();
        }

        List<Evidence> result = new ArrayList<>(paths.size());
        for (var f : staticFindings) {
            if (!"STATIC_SAME_PATH_TYPE_CANDIDATE_ONLY".equals(f.state())) {
                result.add(new Evidence(f.targetPath(), f.state(),
                    null, null, "BLOCKED_STATIC_POLICY",
                    false, false, false, false));
                continue;
            }
            String sourceType = nativeType(historic, f.targetPath());
            String targetType = nativeType(target, f.targetPath());
            String state;
            if (sourceType == null || targetType == null) {
                state = "BLOCKED_NATIVE_FIELD_UNRESOLVED";
            } else if (!sourceType.equals(targetType)) {
                state = "BLOCKED_NATIVE_TYPE_CHANGE";
            } else if (!sourceType.equals(f.sourceType()) ||
                    !targetType.equals(f.targetType())) {
                state = "BLOCKED_STATIC_NATIVE_DISAGREEMENT";
            } else {
                state = "NATIVE_SAME_PATH_TYPE_CANDIDATE_ONLY";
            }
            result.add(new Evidence(f.targetPath(), f.state(),
                sourceType, targetType, state,
                false, false, false, false));
        }
        return List.copyOf(result);
    }

    public record ExplicitEvidence(ExplicitMappingManifest.FieldRule rule, String staticState,
                                   String sourceNativeType, String targetNativeType, String state) {
        public boolean canTransfer() { return false; }
    }

    public static List<ExplicitEvidence> inspectExplicit(String source, String target,
            ExplicitMappingManifest manifest, JavaRosaFormLoader loader) {
        if (loader == null) throw new IllegalArgumentException("Independent JavaRosa parser required");
        var findings = StaticTypedMappingFieldPolicy.inspectExplicit(source, target, manifest);
        FormDef historical = null, current = null;
        if (findings.stream().anyMatch(f -> "STATIC_EXPLICIT_CANDIDATE_ONLY".equals(f.state()))) {
            try {
                historical = loader.loadFormDef(source);
                current = loader.loadFormDef(target);
            } catch (RuntimeException failure) {
                historical = null;
                current = null;
            }
        }
        List<ExplicitEvidence> result = new ArrayList<>();
        for (var finding : findings) {
            var rule = finding.rule();
            String a = null, b = null, state;
            if (!"STATIC_EXPLICIT_CANDIDATE_ONLY".equals(finding.state())) {
                state = "BLOCKED_STATIC_POLICY";
            } else if (historical == null || current == null) {
                state = "BLOCKED_NATIVE_FORM_PARSE";
            } else {
                a = nativeType(historical, rule.sourcePath());
                b = nativeType(current, rule.targetPath());
                state = rule.type().name().equals(a) && rule.type().name().equals(b)
                        ? "NATIVE_EXPLICIT_CANDIDATE_ONLY"
                        : "BLOCKED_STATIC_NATIVE_DISAGREEMENT";
            }
            result.add(new ExplicitEvidence(rule, finding.state(), a, b, state));
        }
        return List.copyOf(result);
    }

    private static String nativeType(FormDef def, String path) {
        if (def == null || def.getMainInstance() == null ||
                def.getMainInstance().getRoot() == null) return null;
        TreeElement node = def.getMainInstance().getRoot();
        String[] parts = path.substring(1).split("/");
        if (parts.length < 2 || !parts[0].equals(node.getName())) return null;

        // Paths were already checked by the strict static policy. Reject
        // ambiguous/repeated nodes and templates instead of choosing a row.
        for (int i = 1; i < parts.length; i++) {
            List<TreeElement> children = node.getChildrenWithName(parts[i]);
            if (children == null || children.size() != 1) return null;
            node = children.get(0);
        }
        if (!node.isLeaf()) return null;
        return switch (node.getDataType()) {
            case Constants.DATATYPE_TEXT -> "TEXT";
            case Constants.DATATYPE_INTEGER -> "INTEGER";
            case Constants.DATATYPE_DECIMAL -> "DECIMAL";
            case Constants.DATATYPE_DATE -> "DATE";
            default -> null;
        };
    }
}
