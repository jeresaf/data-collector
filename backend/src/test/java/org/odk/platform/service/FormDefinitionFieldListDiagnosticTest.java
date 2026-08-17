package org.odk.platform.service;

import org.javarosa.form.api.FormEntryController;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FormDefinitionFieldListDiagnosticTest {
    private static final String FORM = """
      <?xml version="1.0" encoding="UTF-8"?>
      <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns="http://www.w3.org/2002/xforms">
        <h:head><h:title>Test</h:title><model><instance><data id="test"><details><name/><date/><time/></details></data></instance>
        <bind nodeset="/data/details/name" type="string" required="true()"/>
        <bind nodeset="/data/details/date" type="date" required="true()"/>
        <bind nodeset="/data/details/time" type="time" required="true()"/>
        </model></h:head>
        <h:body>
          <group ref="/data/details" appearance="field-list"><label>Details</label>
            <input ref="/data/details/name"><label>Name</label></input>
            <input ref="/data/details/date"><label>Date</label></input>
            <input ref="/data/details/time"><label>Time</label></input>
          </group>
        </h:body>
      </h:html>
      """;

    @Test
    void fieldListTargetsUseResolvableInstancePaths() {
        FormDefinition form = FormDefinition.createNew(FORM, "v1");
        assertEquals(FormEntryController.EVENT_GROUP, form.stepToNextEvent());
        assertTrue(form.isCurrentFieldListGroup());
        assertEquals("details", form.getCurrentFieldListPath());
        assertEquals("details[1]", form.getCurrentFieldListInstancePath());

        List<FormDefinition.FieldListQuestionTarget> targets = form.getCurrentFieldListQuestionTargets();
        assertEquals(3, targets.size());
        assertEquals("details[1]/name[1]", targets.get(0).instancePath());
        assertEquals("details[1]/date[1]", targets.get(1).instancePath());
        assertEquals("details[1]/time[1]", targets.get(2).instancePath());

        for (FormDefinition.FieldListQuestionTarget target : targets) {
            FormDefinition fresh = FormDefinition.createNew(FORM, "v1");
            assertTrue(
                    fresh.moveToQuestion(target.path(), target.instancePath(), null, null),
                    () -> "Field-list target did not resolve: " + target);
        }

        assertEquals(FormEntryController.EVENT_QUESTION, form.stepToNextEvent());
        assertTrue(form.isCurrentFieldListGroup());
        assertEquals(3, form.getCurrentFieldListQuestionTargets().size());
    }

    @Test
    void screenNavigationStopsOnFieldListButSkipsOrdinaryGroups() {
        String ordinaryGroupForm = FORM.replace(" appearance=\"field-list\"", "");
        FormDefinition ordinary = FormDefinition.createNew(ordinaryGroupForm, "v1");
        assertEquals(FormEntryController.EVENT_QUESTION, ordinary.stepToNextScreenEvent());
        assertEquals("details/name", ordinary.getCurrentQuestionPath());

        FormDefinition fieldList = FormDefinition.createNew(FORM, "v1");
        assertEquals(FormEntryController.EVENT_GROUP, fieldList.stepToNextScreenEvent());
        assertTrue(fieldList.isCurrentFieldListGroup());
    }
}
