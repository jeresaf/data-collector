package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SubmissionXmlParserTest {
    private final SubmissionXmlParser parser = new SubmissionXmlParser(mock(JavaRosaFormLoader.class));

    @Test
    void extractsInstanceAndFormIdsAndHandlesMalformedXml() {
        String xml = "<data id=\"household\"><meta><instanceID>uuid:123</instanceID></meta></data>";
        assertEquals("uuid:123", parser.extractInstanceId(xml));
        assertEquals("household", parser.extractFormId(xml));
        assertNull(parser.extractInstanceId("<data/>"));
        assertNull(parser.extractFormId("<root/>"));
    }
}
