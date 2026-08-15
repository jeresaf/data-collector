package org.odk.platform.service;

import org.javarosa.core.model.FormDef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FormXmlContentStoreTest {
    @AfterEach void clear() { FormXmlContentStore.clear(); }

    @Test
    void storeUsesFormDefIdentityAndSupportsRemoval() {
        FormDef first = new FormDef();
        FormDef second = new FormDef();
        FormXmlContentStore.store(first, "<a/>");
        FormXmlContentStore.store(second, "<b/>");
        assertEquals("<a/>", FormXmlContentStore.get(first));
        assertEquals("<b/>", FormXmlContentStore.get(second));
        assertNull(FormXmlContentStore.get(null));
        FormXmlContentStore.remove(first);
        assertNull(FormXmlContentStore.get(first));
        assertEquals("<b/>", FormXmlContentStore.get(second));
    }
}
