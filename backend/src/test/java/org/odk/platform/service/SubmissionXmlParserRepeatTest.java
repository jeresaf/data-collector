package org.odk.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.javarosa.core.model.FormDef;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubmissionXmlParserRepeatTest {

    private static final String FORM_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns:jr="http://openrosa.org/javarosa"
                    xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Repeat Reporting</h:title>
                    <model>
                        <instance>
                            <data id="repeat_reporting">
                                <household_name/>
                                <members jr:template="">
                                    <name/>
                                    <visits jr:template="">
                                        <date/>
                                        <notes/>
                                    </visits>
                                </members>
                                <meta><instanceID/></meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/household_name" type="string"/>
                        <bind nodeset="/data/members/name" type="string"/>
                        <bind nodeset="/data/members/visits/date" type="string"/>
                        <bind nodeset="/data/members/visits/notes" type="string"/>
                        <bind nodeset="/data/meta/instanceID" type="string" readonly="true()"/>
                    </model>
                </h:head>
                <h:body>
                    <input ref="/data/household_name"><label>Household</label></input>
                    <repeat nodeset="/data/members">
                        <label>Member</label>
                        <input ref="/data/members/name"><label>Name</label></input>
                        <repeat nodeset="/data/members/visits">
                            <label>Visit</label>
                            <input ref="/data/members/visits/date"><label>Date</label></input>
                            <input ref="/data/members/visits/notes"><label>Notes</label></input>
                        </repeat>
                    </repeat>
                </h:body>
            </h:html>
            """;

    private static final String SUBMISSION_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <data id="repeat_reporting">
                <household_name>Smith</household_name>
                <members>
                    <name>John</name>
                    <visits><date>2026-08-01</date><notes>Checkup</notes></visits>
                    <visits><date>2026-08-08</date><notes>Follow-up</notes></visits>
                </members>
                <members>
                    <name>Jane</name>
                    <visits><date>2026-08-03</date><notes>Initial</notes></visits>
                </members>
                <meta><instanceID>uuid:nested-repeat-test</instanceID></meta>
            </data>
            """;

    @Test
    void realJavaRosaXmlPreservesNestedRepeatArraysAndFlattensThem() {
        JavaRosaFormLoader loader = mock(JavaRosaFormLoader.class);
        FormDefinition definition = FormDefinition.createNew(FORM_XML, "repeat-reporting");
        FormDef formDef = definition.getFormDef();
        when(loader.getXmlContent(formDef)).thenReturn(FORM_XML);

        SubmissionXmlParser parser = new SubmissionXmlParser(loader);
        JsonNode json = parser.parseSubmission(SUBMISSION_XML, formDef);

        assertEquals("Smith", json.path("household_name").asText());
        assertTrue(json.path("members").isArray());
        assertEquals(2, json.path("members").size());
        assertEquals("John", json.path("members").get(0).path("name").asText());
        assertTrue(json.path("members").get(1).path("visits").isArray());
        assertEquals(1, json.path("members").get(1).path("visits").size());
        assertEquals(
                "2026-08-03",
                json.path("members").get(1).path("visits").get(0).path("date").asText()
        );

        SubmissionProcessingService processing = new SubmissionProcessingService(parser, loader);
        Map<String, Object> flattened = processing.flattenAnswers(json);

        assertEquals("Smith", flattened.get("household_name"));
        assertEquals("John", flattened.get("members[0]/name"));
        assertEquals("2026-08-08", flattened.get("members[0]/visits[1]/date"));
        assertEquals("Jane", flattened.get("members[1]/name"));
        assertEquals("2026-08-03", flattened.get("members[1]/visits[0]/date"));
        assertEquals("uuid:nested-repeat-test", flattened.get("meta/instanceID"));
    }
}
