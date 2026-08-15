package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused regression coverage for the OpenRosa JavaRosa restored-instance path.
 *
 * This deliberately does not create FormDefinition first. JavaRosaFormLoader is
 * the parsing gateway and must register everything needed to restore typed
 * instance values on its own.
 */
class SubmissionXmlParserValidationTest {

    private static final String FORM_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns:orx="http://openrosa.org/xforms"
                    xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Submission Test Form</h:title>
                    <model>
                        <instance>
                            <data id="test_submission_form" version="1.0"
                                  xmlns:jr="http://openrosa.org/javarosa"
                                  xmlns:orx="http://openrosa.org/xforms">
                                <name/>
                                <age/>
                                <photo/>
                                <audio/>
                                <meta><instanceID/></meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/name" type="string"/>
                        <bind nodeset="/data/age" type="int"/>
                        <bind nodeset="/data/photo" type="binary"/>
                        <bind nodeset="/data/audio" type="binary"/>
                    </model>
                    <itext>
                        <translation lang="en" default="true">
                            <text id="/data/name:label"><value>Name</value></text>
                            <text id="/data/age:label"><value>Age</value></text>
                            <text id="/data/photo:label"><value>Photo</value></text>
                            <text id="/data/audio:label"><value>Audio</value></text>
                        </translation>
                    </itext>
                </h:head>
                <h:body>
                    <input ref="/data/name"><label ref="jr:itext('/data/name:label')"/></input>
                    <input ref="/data/age"><label ref="jr:itext('/data/age:label')"/></input>
                    <upload ref="/data/photo" mediatype="image/*"><label ref="jr:itext('/data/photo:label')"/></upload>
                    <upload ref="/data/audio" mediatype="audio/*"><label ref="jr:itext('/data/audio:label')"/></upload>
                </h:body>
            </h:html>
            """;

    private final SubmissionXmlParser parser =
            new SubmissionXmlParser(new JavaRosaFormLoader());

    @Test
    void restoredTypedInstance_validIntegerPassesWithoutFormDefinitionBootstrap() {
        String instance = """
                <data id="test_submission_form" version="1.0">
                    <name>Valid Submission</name>
                    <age>25</age>
                    <meta><instanceID>uuid:valid-int</instanceID></meta>
                </data>
                """;

        var validated = parser.parseAndValidate(FORM_XML, instance, Map.of());

        assertEquals("Valid Submission", validated.jsonContent().path("name").asText());
        assertEquals("25", validated.jsonContent().path("age").asText());
        assertEquals("uuid:valid-int",
                validated.jsonContent().path("meta").path("instanceID").asText());
    }

    @Test
    void omittedOptionalQuestionsRemainValid() {
        String instance = """
                <data id="test_submission_form" version="1.0">
                    <age>25</age>
                    <meta><instanceID>uuid:optional-omission</instanceID></meta>
                </data>
                """;

        var validated = parser.parseAndValidate(FORM_XML, instance, Map.of());

        assertEquals("25", validated.jsonContent().path("age").asText());
    }

    @Test
    void presentButMalformedTypedValueIsRejected() {
        String instance = """
                <data id="test_submission_form" version="1.0">
                    <name>Bad age</name>
                    <age>not-an-integer</age>
                    <meta><instanceID>uuid:invalid-int</instanceID></meta>
                </data>
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseAndValidate(FORM_XML, instance, Map.of()));

        assertTrue(error.getMessage().contains("age"));
    }
}
