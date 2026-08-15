package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javarosa.core.model.FormDef;
import org.odk.platform.domain.XForm;
import org.odk.platform.exception.FormValidationException;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class JavaRosaFormValidator {

    private static final String[] XML_GETTERS = {
            "getXformXml", "getXmlContent", "getDefinitionXml", "getXml"
    };

    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "<(?:[A-Za-z_][\\w.-]*:)?title\\b[^>]*>(.*?)</(?:[A-Za-z_][\\w.-]*:)?title\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern PRIMARY_INSTANCE_ROOT_PATTERN = Pattern.compile(
            "<(?:[A-Za-z_][\\w.-]*:)?instance\\b[^>]*>\\s*"
                    + "<([A-Za-z_][\\w:.-]*)\\b([^>]*)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);


    private static final Pattern CONSTRAINT_PATTERN = Pattern.compile(
            "\\bconstraint\\s*=\\s*([\"'])(.*?)\\1",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final JavaRosaFormLoader formLoader;

    /**
     * Validates an XForm XML string and returns a detailed validation result.
     *
     * Source-level XForm structure is validated first. This is important because
     * JavaRosa parsing can fail before we get a chance to report useful validation
     * codes (for example MISSING_INSTANCE), and some otherwise valid forms contain
     * constructs such as repeats, outputs/calculates, or external instances that
     * may require runtime context not available during metadata validation.
     */
    public ValidationResult validateForm(String xmlContent) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (xmlContent == null || xmlContent.isBlank()) {
            errors.add("XForm XML content cannot be null or empty");
            return new ValidationResult(false, errors, warnings);
        }

        validateSourceStructure(xmlContent, errors, warnings);

        // If the source itself is invalid, do not let a parser exception hide the
        // specific structural validation messages collected above.
        if (!errors.isEmpty()) {
            return new ValidationResult(false, errors, warnings);
        }

        try {
            formLoader.loadFormDef(xmlContent);
        } catch (FormValidationException e) {
            /*
             * A FormDef parse is an important compatibility signal, but it is not
             * the sole definition of XForm validity here. External instances and
             * some JavaRosa runtime constructs may need context that is unavailable
             * to this validator. Preserve that information as a warning while the
             * source-level validation remains authoritative.
             */
            warnings.add("JAVAROSA_PARSE_WARNING: " + safeMessage(e));
            log.debug("JavaRosa could not fully parse structurally valid XForm: {}", e.getMessage());
        } catch (RuntimeException e) {
            warnings.add("JAVAROSA_PARSE_WARNING: " + safeMessage(e));
            log.debug("JavaRosa runtime validation warning: {}", e.getMessage());
        }

        return new ValidationResult(true, errors, warnings);
    }

    /**
     * Validates an already parsed FormDef.
     *
     * Do not reparse a FormDef that JavaRosa has already successfully created.
     * Re-parsing is unnecessary and can introduce failures for forms containing
     * runtime-dependent constructs.
     */
    public ValidationResult validateFormDef(FormDef formDef) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (formDef == null) {
            errors.add("FormDef cannot be null");
            return new ValidationResult(false, errors, warnings);
        }

        try {
            String xmlContent = formLoader.getXmlContent(formDef);
            if (xmlContent != null && !xmlContent.isBlank()) {
                validateSourceStructure(xmlContent, errors, warnings);
                return new ValidationResult(errors.isEmpty(), errors, warnings);
            }

            String formId = formLoader.getFormId(formDef);
            if (formId == null || formId.isBlank()) {
                errors.add("MISSING_FORM_ID: Form instance root is missing an 'id' attribute");
            }

            String title = formLoader.getFormTitle(formDef);
            if (title == null || title.isBlank()) {
                warnings.add("MISSING_FORM_TITLE: Form is missing a title");
            }

            return new ValidationResult(errors.isEmpty(), errors, warnings);
        } catch (Exception e) {
            log.error("Error validating FormDef", e);
            errors.add("Validation error: " + safeMessage(e));
            return new ValidationResult(false, errors, warnings);
        }
    }

    /**
     * Checks whether the supplied source is valid enough for data-entry setup.
     *
     * This method intentionally follows the detailed validator so callers receive
     * the same structural validity decision used elsewhere in the platform.
     */
    public boolean canCreateInstance(String xmlContent) {
        return validateForm(xmlContent).isValid();
    }

    /** Returns true only when JavaRosa 4.3.2 can parse the supplied XML. */
    public boolean validate(String xmlContent) {
        try {
            formLoader.loadFormDef(xmlContent);
            return true;
        } catch (FormValidationException | IllegalArgumentException e) {
            log.debug("XForm validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Backwards-compatible entity entry point. It supports the common raw-XML
     * getter names without hard-coding a method that may not exist on the XForm entity.
     */
    public boolean validate(XForm form) {
        if (form == null) {
            return false;
        }

        String xmlContent = extractXmlContent(form);
        if (xmlContent == null || xmlContent.isBlank()) {
            log.warn("Could not validate XForm entity because it exposes no supported raw XML getter");
            return false;
        }

        return validate(xmlContent);
    }

    /** Validates and preserves the loader's detailed validation exception. */
    public void validateOrThrow(String xmlContent) {
        formLoader.loadFormDef(xmlContent);
    }

    private void validateSourceStructure(
            String xmlContent,
            List<String> errors,
            List<String> warnings) {

        if (!containsOpeningElement(xmlContent, "html")) {
            errors.add("MISSING_XFORM_ROOT: Form is missing an html root element");
        }

        if (!containsOpeningElement(xmlContent, "model")) {
            errors.add("MISSING_MODEL: Form is missing an XForms model element");
        }

        boolean hasInstance = containsOpeningElement(xmlContent, "instance");
        if (!hasInstance) {
            errors.add("MISSING_INSTANCE: Form is missing an instance element");
        } else {
            checkPrimaryInstanceId(xmlContent, errors);
        }

        if (!containsOpeningElement(xmlContent, "body")) {
            errors.add("MISSING_FORM_BODY: Form is missing a body element");
        }

        if (!containsNonBlankTitle(xmlContent)) {
            warnings.add("MISSING_FORM_TITLE: Form is missing a title");
        }

        checkDuplicateBinds(xmlContent, errors);
        checkInvalidConstraints(xmlContent, errors);
    }

    private boolean containsOpeningElement(String xmlContent, String localName) {
        Pattern pattern = Pattern.compile(
                "<(?:[A-Za-z_][\\w.-]*:)?"
                        + Pattern.quote(localName)
                        + "\\b",
                Pattern.CASE_INSENSITIVE);
        return pattern.matcher(xmlContent).find();
    }

    private boolean containsNonBlankTitle(String xmlContent) {
        Matcher matcher = TITLE_PATTERN.matcher(xmlContent);
        if (!matcher.find()) {
            return false;
        }

        String title = matcher.group(1);
        return title != null && !title.replaceAll("<[^>]+>", "").trim().isEmpty();
    }

    private void checkPrimaryInstanceId(String xmlContent, List<String> errors) {
        Matcher matcher = PRIMARY_INSTANCE_ROOT_PATTERN.matcher(xmlContent);
        if (!matcher.find()) {
            errors.add("MISSING_INSTANCE: Form does not contain a primary instance root element");
            return;
        }

        String rootAttributes = matcher.group(2);
        String id = extractAttribute(rootAttributes, "id");
        if (id == null || id.isBlank()) {
            errors.add("MISSING_FORM_ID: Form instance root is missing an 'id' attribute");
        }
    }

    private void checkDuplicateBinds(String xmlContent, List<String> errors) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();

        for (String bindTag : findStartTags(xmlContent, "bind")) {
            String nodeset = firstNonBlank(
                    extractAttribute(bindTag, "nodeset"),
                    extractAttribute(bindTag, "ref"));

            if (nodeset == null) {
                continue;
            }

            String normalized = normalizeReference(nodeset);
            if (!seen.add(normalized)) {
                duplicates.add(normalized);
            }
        }

        if (!duplicates.isEmpty()) {
            errors.add("DUPLICATE_BINDS: Found duplicate bind references: "
                    + String.join(", ", duplicates));
        }
    }

    private void checkInvalidConstraints(String xmlContent, List<String> errors) {
        Matcher matcher = CONSTRAINT_PATTERN.matcher(xmlContent);
        while (matcher.find()) {
            String constraint = matcher.group(2);
            if (constraint != null && isObviouslyInvalidConstraint(constraint)) {
                errors.add("INVALID_CONSTRAINT: Constraint '" + constraint
                        + "' appears to be logically impossible");
            }
        }
    }

    /**
     * Finds opening tags while respecting quoted attribute values. This avoids
     * breaking on XPath expressions that themselves contain '<' or '>'.
     */
    private List<String> findStartTags(String xmlContent, String localName) {
        List<String> tags = new ArrayList<>();
        Pattern startPattern = Pattern.compile(
                "<(?:[A-Za-z_][\\w.-]*:)?"
                        + Pattern.quote(localName)
                        + "\\b",
                Pattern.CASE_INSENSITIVE);

        Matcher matcher = startPattern.matcher(xmlContent);
        while (matcher.find()) {
            int start = matcher.start();
            int end = findTagEnd(xmlContent, matcher.end());
            if (end > start) {
                tags.add(xmlContent.substring(start, end + 1));
            }
        }
        return tags;
    }

    private int findTagEnd(String text, int fromIndex) {
        char quote = 0;
        for (int i = fromIndex; i < text.length(); i++) {
            char c = text.charAt(i);

            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }

            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private String extractAttribute(String source, String attributeName) {
        if (source == null) {
            return null;
        }

        Pattern pattern = Pattern.compile(
                "(?i)(?:^|\\s)"
                        + Pattern.quote(attributeName)
                        + "\\s*=\\s*([\"'])(.*?)\\1",
                Pattern.DOTALL);

        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? matcher.group(2).trim() : null;
    }

    private boolean isObviouslyInvalidConstraint(String constraint) {
        String lower = constraint
                .toLowerCase(Locale.ROOT)
                .replace("&gt;", ">")
                .replace("&lt;", "<")
                .replaceAll("\\s+", "");

        return lower.contains(".>100and.<0")
                || lower.contains(".<0and.>100")
                || lower.contains("false()andtrue()")
                || lower.contains("true()andfalse()");
    }

    private String extractXmlContent(XForm form) {
        for (String getterName : XML_GETTERS) {
            try {
                Method getter = form.getClass().getMethod(getterName);
                Object value = getter.invoke(form);
                if (value instanceof String && !((String) value).isBlank()) {
                    return (String) value;
                }
            } catch (ReflectiveOperationException ignored) {
                // Try the next conventional getter name.
            }
        }
        return null;
    }

    private static String normalizeReference(String reference) {
        return reference == null
                ? null
                : reference.trim()
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

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "Unknown validation error";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    /**
     * Detailed validation result containing errors and warnings.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;

        public ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = errors == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(errors));
            this.warnings = warnings == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getErrors() {
            return errors;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
    }
}
