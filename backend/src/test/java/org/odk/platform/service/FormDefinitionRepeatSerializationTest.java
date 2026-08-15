package org.odk.platform.service;

import org.javarosa.core.model.data.StringData;
import org.javarosa.form.api.FormEntryController;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FormDefinitionRepeatSerializationTest {

    private static final String REPEAT_COUNT_XFORM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms"
                    xmlns:jr="http://openrosa.org/javarosa">
                <h:head>
                    <h:title>Repeat Count Form</h:title>
                    <model>
                        <instance>
                            <data id="repeat_count_form">
                                <repeat_count>2</repeat_count>
                                <members>
                                    <member jr:template="">
                                        <name/>
                                    </member>
                                </members>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/repeat_count" type="int"/>
                        <bind nodeset="/data/members/member/name" type="string"/>
                    </model>
                </h:head>
                <h:body>
                    <group ref="/data/members">
                        <label>Members</label>
                        <repeat nodeset="/data/members/member" jr:count="/data/repeat_count">
                            <input ref="/data/members/member/name">
                                <label>Member Name</label>
                            </input>
                        </repeat>
                    </group>
                </h:body>
            </h:html>
            """;

    @Test
    void finalizeAndSerialize_preservesEveryRepeatMultiplicity() throws Exception {
        FormDefinition form = FormDefinition.createNew(REPEAT_COUNT_XFORM, "repeat-serialization-test");

        assertEquals(FormEntryController.EVENT_REPEAT, form.stepToNextEvent());
        assertEquals(FormEntryController.EVENT_QUESTION, form.stepToNextEvent());
        assertEquals(0, form.getCurrentRepeatIndex());
        form.answerQuestion(new StringData("Member 1"));

        assertEquals(FormEntryController.EVENT_REPEAT, form.stepToNextEvent());
        assertEquals(1, form.getCurrentRepeatIndex());
        assertEquals(FormEntryController.EVENT_QUESTION, form.stepToNextEvent());
        assertEquals(1, form.getCurrentRepeatIndex());
        form.answerQuestion(new StringData("Member 2"));

        assertEquals(FormEntryController.EVENT_END_OF_FORM, form.stepToNextEvent());

        String xml = form.finalizeAndSerialize();
        assertNotNull(xml);

        Document document = parseXml(xml);
        NodeList members = document.getElementsByTagNameNS("*", "member");
        assertEquals(2, members.getLength(), "Both JavaRosa repeat instances must be serialized");

        assertEquals("Member 1", childText((Element) members.item(0), "name"));
        assertEquals("Member 2", childText((Element) members.item(1), "name"));
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String childText(Element parent, String localName) {
        NodeList children = parent.getElementsByTagNameNS("*", localName);
        assertEquals(1, children.getLength());
        return children.item(0).getTextContent();
    }
}
