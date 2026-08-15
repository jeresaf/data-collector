package org.odk.platform.service;

import org.javarosa.form.api.FormEntryController;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FormDefinitionNestedStructureTest {

    private static final String NESTED_GROUP_XFORM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Nested Groups</h:title>
                    <model>
                        <instance>
                            <data id="nested_groups">
                                <person>
                                    <contact>
                                        <phone/>
                                    </contact>
                                </person>
                            </data>
                        </instance>
                        <bind nodeset="/data/person/contact/phone" type="string"/>
                    </model>
                </h:head>
                <h:body>
                    <group ref="/data/person">
                        <label>Person</label>
                        <group ref="/data/person/contact">
                            <label>Contact</label>
                            <input ref="/data/person/contact/phone">
                                <label>Phone</label>
                            </input>
                        </group>
                    </group>
                </h:body>
            </h:html>
            """;

    private static final String NESTED_REPEAT_XFORM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns:jr="http://openrosa.org/javarosa"
                    xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Nested Repeats</h:title>
                    <model>
                        <instance>
                            <data id="nested_repeats">
                                <members jr:template="">
                                    <name/>
                                    <visits jr:template="">
                                        <date/>
                                    </visits>
                                </members>
                            </data>
                        </instance>
                        <bind nodeset="/data/members/name" type="string"/>
                        <bind nodeset="/data/members/visits/date" type="string"/>
                    </model>
                </h:head>
                <h:body>
                    <repeat nodeset="/data/members">
                        <label>Member</label>
                        <input ref="/data/members/name"><label>Name</label></input>
                        <repeat nodeset="/data/members/visits">
                            <label>Visit</label>
                            <input ref="/data/members/visits/date"><label>Date</label></input>
                        </repeat>
                    </repeat>
                </h:body>
            </h:html>
            """;

    private static final String NESTED_REPEAT_INSTANCE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <data id="nested_repeats">
                <members>
                    <name>Member 1</name>
                    <visits><date>2026-08-01</date></visits>
                </members>
                <members>
                    <name>Member 2</name>
                    <visits><date>2026-08-02</date></visits>
                </members>
            </data>
            """;

    @Test
    void nestedGroupsExposeFullHierarchyForStructuredRendering() {
        FormDefinition form = FormDefinition.createNew(NESTED_GROUP_XFORM, "nested-groups");

        assertEquals(FormEntryController.EVENT_GROUP, form.stepToNextEvent());
        assertEquals(1, form.getCurrentGroupHierarchy().size());
        assertEquals("Person", form.getCurrentGroupHierarchy().get(0).label());
        assertEquals(0, form.getCurrentGroupHierarchy().get(0).depth());

        assertEquals(FormEntryController.EVENT_GROUP, form.stepToNextEvent());
        assertEquals(2, form.getCurrentGroupHierarchy().size());
        assertEquals("Contact", form.getCurrentGroupHierarchy().get(1).label());
        assertEquals(1, form.getCurrentGroupHierarchy().get(1).depth());

        assertEquals(FormEntryController.EVENT_QUESTION, form.stepToNextEvent());
        assertEquals("person/contact/phone", form.getCurrentQuestionPath());
        assertEquals(2, form.getCurrentGroupHierarchy().size());
        assertTrue(form.getCurrentQuestionInstancePath().contains("phone"));
    }

    @Test
    void exactInstancePathTargetsSameInnerRepeatIndexUnderDifferentOuterRepeats() {
        FormDefinition source = FormDefinition.createFromInstance(
                NESTED_REPEAT_XFORM,
                NESTED_REPEAT_INSTANCE,
                "nested-repeats"
        );

        List<String> dateInstancePaths = new ArrayList<>();
        int event = source.getCurrentEvent();
        int guard = 0;
        while (event != FormEntryController.EVENT_END_OF_FORM && guard++ < 100) {
            event = source.stepToNextEvent();
            if (event == FormEntryController.EVENT_QUESTION
                    && "members/visits/date".equals(source.getCurrentQuestionPath())) {
                dateInstancePaths.add(source.getCurrentQuestionInstancePath());
            }
        }

        assertEquals(2, dateInstancePaths.size());
        assertNotEquals(dateInstancePaths.get(0), dateInstancePaths.get(1));
        assertTrue(dateInstancePaths.get(0).startsWith("members["));
        assertFalse(dateInstancePaths.get(0).startsWith("data["));

        FormDefinition target = FormDefinition.createFromInstance(
                NESTED_REPEAT_XFORM,
                NESTED_REPEAT_INSTANCE,
                "nested-repeats-target"
        );

        assertTrue(target.moveToQuestion(
                "members/visits/date",
                dateInstancePaths.get(1),
                "members/visits",
                0
        ));
        assertEquals(dateInstancePaths.get(1), target.getCurrentQuestionInstancePath());
        assertEquals("2026-08-02", target.getCurrentAnswerText());

        List<FormDefinition.GroupContext> hierarchy = target.getCurrentGroupHierarchy();
        assertEquals(2, hierarchy.stream().filter(FormDefinition.GroupContext::repeat).count());
        assertEquals(1, hierarchy.get(0).repeatIndex());
        assertEquals(0, hierarchy.get(1).repeatIndex());
    }
}
