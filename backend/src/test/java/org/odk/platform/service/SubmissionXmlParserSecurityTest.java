package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SubmissionXmlParserSecurityTest {

    private final SubmissionXmlParser parser =
            new SubmissionXmlParser(mock(JavaRosaFormLoader.class));

    @Test
    void parseEnvelope_readsNamespacedIdentifiersWithoutStringScanning() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <x:data xmlns:x="urn:test" id="household" version="20260814">
                    <x:meta>
                        <x:instanceID>uuid:current</x:instanceID>
                        <x:deprecatedID>uuid:old</x:deprecatedID>
                    </x:meta>
                </x:data>
                """;

        var envelope = parser.parseEnvelope(xml);
        assertEquals("household", envelope.formId());
        assertEquals("20260814", envelope.version());
        assertEquals("uuid:current", envelope.instanceId());
        assertEquals("uuid:old", envelope.deprecatedId());
    }

    @Test
    void parseEnvelope_rejectsDoctypeAndExternalEntityPayloads() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE data [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <data id="unsafe"><name>&xxe;</name></data>
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseEnvelope(xml));
        assertTrue(error.getMessage().contains("Invalid") || error.getMessage().contains("unsafe"));
    }

    @Test
    void compatibilityExtractors_useTheSecureEnvelopeParser() {
        String xml = """
                <data id='demo' version='1'>
                    <meta><instanceId>uuid:one</instanceId><deprecatedId>uuid:zero</deprecatedId></meta>
                </data>
                """;
        assertEquals("demo", parser.extractFormId(xml));
        assertEquals("uuid:one", parser.extractInstanceId(xml));
        assertEquals("uuid:zero", parser.extractDeprecatedId(xml));
    }
}
