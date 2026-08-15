package org.odk.platform.service;

import org.javarosa.core.model.FormDef;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Keeps the source XML associated with a parsed FormDef.
 *
 * FormDef does not expose the original XForm XML in JavaRosa 4.3.2. Identity
 * comparison is intentional because a FormDef is a mutable, per-parse object.
 * Call remove when a long-lived FormDef is discarded.
 */
public final class FormXmlContentStore {

    private static final Map<FormDef, String> XML_CONTENT_STORE =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private FormXmlContentStore() {
    }

    public static void store(FormDef formDef, String xmlContent) {
        if (formDef != null && xmlContent != null) {
            XML_CONTENT_STORE.put(formDef, xmlContent);
        }
    }

    public static String get(FormDef formDef) {
        return formDef == null ? null : XML_CONTENT_STORE.get(formDef);
    }

    public static void remove(FormDef formDef) {
        if (formDef != null) {
            XML_CONTENT_STORE.remove(formDef);
        }
    }

    public static void clear() {
        XML_CONTENT_STORE.clear();
    }
}
