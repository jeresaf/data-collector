package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compatibility coverage for JavaRosa-backed XPath/XForm functions used by the
 * web runtime. These tests intentionally evaluate through FormDefinition so a
 * regression in the wrapper's EvaluationContext is detected even when the
 * underlying JavaRosa function itself is working correctly.
 */
class FormDefinitionAdvancedXFormLogicTest {

    private static final String ADVANCED_LOGIC_XFORM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms"
                    xmlns:jr="http://openrosa.org/javarosa">
                <h:head>
                    <h:title>Advanced Logic Compatibility</h:title>
                    <model>
                        <instance>
                            <data id="advanced_logic" version="1.0">
                                <colors/>
                                <city/>
                                <facility_label/>
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
                        <instance id="facilities">
                            <root>
                                <item><id>f1</id><label>Clinic A</label></item>
                                <item><id>f2</id><label>Clinic B</label></item>
                            </root>
                        </instance>
                        <bind nodeset="/data/colors" type="select"/>
                        <bind nodeset="/data/city" type="select1"/>
                        <bind nodeset="/data/facility_label" type="string"
                              calculate="instance('facilities')/root/item[id = 'f2']/label"/>
                        <bind nodeset="/data/members/member/name" type="string"/>
                    </model>
                </h:head>
                <h:body>
                    <select ref="/data/colors">
                        <label>Colors</label>
                        <item><label>Red</label><value>red</value></item>
                        <item><label>Blue</label><value>blue</value></item>
                        <item><label>Green</label><value>green</value></item>
                    </select>
                    <select1 ref="/data/city">
                        <label>City</label>
                        <item><label>Kampala</label><value>kla</value></item>
                        <item><label>Entebbe</label><value>ebr</value></item>
                    </select1>
                    <group ref="/data/members">
                        <label>Members</label>
                        <repeat nodeset="/data/members/member">
                            <input ref="/data/members/member/name">
                                <label>Member name</label>
                            </input>
                        </repeat>
                    </group>
                </h:body>
            </h:html>
            """;

    private static final String ADVANCED_LOGIC_INSTANCE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <data id="advanced_logic" version="1.0"
                  xmlns:jr="http://openrosa.org/javarosa">
                <colors>red blue</colors>
                <city>kla</city>
                <facility_label/>
                <members>
                    <member><name>Alice</name></member>
                    <member><name>Bob</name></member>
                </members>
                <meta><instanceID>uuid:advanced-logic-test</instanceID></meta>
            </data>
            """;

    @Test
    void selectedAndCountSelected_useJavaRosaSemantics() {
        FormDefinition form = createForm();

        assertTrue((Boolean) form.evaluateXPath("selected(/data/colors, 'blue')"));
        assertFalse((Boolean) form.evaluateXPath("selected(/data/colors, 'green')"));
        assertNumberEquals(2.0, form.evaluateXPath("count-selected(/data/colors)"));
    }

    @Test
    void indexedRepeatAndCount_canReadRepeatInstances() {
        FormDefinition form = createForm();

        assertEquals(
                "Bob",
                form.evaluateXPath("indexed-repeat(/data/members/member/name, /data/members/member, 2)"));
        assertNumberEquals(2.0, form.evaluateXPath("count(/data/members/member)"));
    }

    @Test
    void position_isScopedToTheCurrentRepeatInstance() {
        FormDefinition form = createForm();

        assertTrue(form.moveToQuestion("members/member/name", "members/member", 1));

        // The current question node itself has multiplicity 1, while its parent
        // repeat instance is the second member. Both forms of position() are
        // useful and confirm that the wrapper is no longer evaluating at root.
        assertNumberEquals(1.0, form.evaluateXPath("position()"));
        assertNumberEquals(2.0, form.evaluateXPath("position(..)"));
    }

    @Test
    void choiceName_usesFormDefFunctionHandlers() {
        FormDefinition form = createForm();

        assertEquals(
                "Kampala",
                form.evaluateXPath("jr:choice-name(/data/city, '/data/city')"));
    }

    @Test
    void instanceLookup_canReadInlineSecondaryInstance() {
        FormDefinition form = createForm();

        // JavaRosa intentionally materializes only secondary instances that are
        // referenced by an instance() expression in the parsed XForm. The
        // /data/facility_label calculate above makes this a real form dependency,
        // rather than an ad-hoc expression introduced only after parsing.
        assertTrue(form.getFormDef().getNonMainInstance("facilities") != null);
        assertEquals(
                "Clinic B",
                form.evaluateXPath("instance('facilities')/root/item[id = 'f2']/label"));
    }

    private static FormDefinition createForm() {
        return FormDefinition.createFromInstance(
                ADVANCED_LOGIC_XFORM,
                ADVANCED_LOGIC_INSTANCE,
                "advanced-logic-test");
    }

    private static void assertNumberEquals(double expected, Object actual) {
        assertTrue(actual instanceof Number, "Expected numeric XPath result but got: " + actual);
        assertEquals(expected, ((Number) actual).doubleValue(), 0.000001);
    }
}
