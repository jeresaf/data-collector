package org.odk.platform.service;

import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.QuestionDef;
import org.javarosa.core.model.FormIndex;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.form.api.FormEntryController;
import org.javarosa.form.api.FormEntryPrompt;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.core.model.data.StringData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class FormDefinitionTest {

    private static final String SIMPLE_XFORM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Simple Form</h:title>
                    <model>
                        <instance>
                            <data id="simple_form" version="1.0.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <name/>
                                <age/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/name" type="string" required="true()"/>
                        <bind nodeset="/data/age" type="int" constraint=". > 0 and . < 120"/>
                    </model>
                </h:head>
                <h:body>
                    <input ref="/data/name">
                        <label>Name</label>
                        <hint>Enter your name</hint>
                    </input>
                    <input ref="/data/age">
                        <label>Age</label>
                    </input>
                </h:body>
            </h:html>
            """;

    private static final String REPEAT_XFORM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Repeat Form</h:title>
                    <model>
                        <instance>
                            <data id="repeat_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <household>
                                    <name/>
                                    <members>
                                        <member jr:template="">
                                            <name/>
                                            <age/>
                                        </member>
                                    </members>
                                </household>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/household/name" type="string" required="true()"/>
                        <bind nodeset="/data/household/members/member" type="repeat"/>
                        <bind nodeset="/data/household/members/member/name" type="string"/>
                        <bind nodeset="/data/household/members/member/age" type="int"/>
                    </model>
                </h:head>
                <h:body>
                    <group ref="/data/household">
                        <label>Household</label>
                        <input ref="/data/household/name">
                            <label>Household Name</label>
                        </input>
                        <repeat nodeset="/data/household/members/member">
                            <label>Household Member</label>
                            <input ref="/data/household/members/member/name">
                                <label>Member Name</label>
                            </input>
                            <input ref="/data/household/members/member/age">
                                <label>Member Age</label>
                            </input>
                        </repeat>
                    </group>
                </h:body>
            </h:html>
            """;

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

    private static final String SELECT_XFORM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Select Form</h:title>
                    <model>
                        <instance>
                            <data id="select_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <gender/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/gender" type="select1">
                            <item>
                                <label>Male</label>
                                <value>male</value>
                            </item>
                            <item>
                                <label>Female</label>
                                <value>female</value>
                            </item>
                        </bind>
                    </model>
                </h:head>
                <h:body>
                    <select1 ref="/data/gender">
                        <label>Gender</label>
                        <item>
                            <label>Male</label>
                            <value>male</value>
                        </item>
                        <item>
                            <label>Female</label>
                            <value>female</value>
                        </item>
                    </select1>
                </h:body>
            </h:html>
            """;

    private static final String CALCULATION_XFORM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Calculation Form</h:title>
                    <model>
                        <instance>
                            <data id="calc_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <a/>
                                <b/>
                                <sum/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/a" type="int"/>
                        <bind nodeset="/data/b" type="int"/>
                        <bind nodeset="/data/sum" type="int" calculate="/data/a + /data/b"/>
                    </model>
                </h:head>
                <h:body>
                    <input ref="/data/a">
                        <label>A</label>
                    </input>
                    <input ref="/data/b">
                        <label>B</label>
                    </input>
                    <input ref="/data/sum">
                        <label>Sum</label>
                    </input>
                </h:body>
            </h:html>
            """;

    private static final String RELEVANT_XFORM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Relevant Form</h:title>
                    <model>
                        <instance>
                            <data id="relevant_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <show_details/>
                                <details/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/show_details" type="boolean"/>
                        <bind nodeset="/data/details" type="string" relevant="/data/show_details = true()"/>
                    </model>
                </h:head>
                <h:body>
                    <input ref="/data/show_details">
                        <label>Show Details</label>
                    </input>
                    <input ref="/data/details">
                        <label>Details</label>
                    </input>
                </h:body>
            </h:html>
            """;

    private FormDefinition formDef;

    @BeforeEach
    void setUp() {
        formDef = FormDefinition.createNew(SIMPLE_XFORM, "test-version");
    }

    @Test
    void createNew_validXForm_returnsFormDefinition() {
        assertNotNull(formDef);
        assertEquals("test-version", formDef.getFormVersionId());
    }

    @Test
    void createNew_invalidXForm_throwsException() {
        String invalidXml = "<invalid/>";
        assertThrows(IllegalArgumentException.class, () -> {
            FormDefinition.createNew(invalidXml, "test");
        });
    }

    @Test
    void createFromInstance_validInstance_returnsFormDefinition() {
        String instanceXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <data id="simple_form" version="1.0.0">
                    <name>John</name>
                    <age>30</age>
                    <meta>
                        <instanceID>uuid:123</instanceID>
                    </meta>
                </data>
                """;
        
        FormDefinition def = FormDefinition.createFromInstance(SIMPLE_XFORM, instanceXml, "test-version");
        assertNotNull(def);
    }

    @Test
    void getFormTitle_returnsTitle() {
        assertEquals("Simple Form", formDef.getFormTitle());
    }

    @Test
    void getXmlFormId_returnsFormId() {
        assertEquals("simple_form", formDef.getXmlFormId());
    }

    @Test
    void getAvailableLocales_returnsArray() {
        String[] locales = formDef.getAvailableLocales();
        assertNotNull(locales);
    }

    @Test
    void getCurrentEvent_initiallyAtBeginning() {
        assertEquals(FormEntryController.EVENT_BEGINNING_OF_FORM, formDef.getCurrentEvent());
    }

    @Test
    void isAtBeginningOfForm_initiallyTrue() {
        assertTrue(formDef.isAtBeginningOfForm());
    }

    @Test
    void isAtEndOfForm_initiallyFalse() {
        assertFalse(formDef.isAtEndOfForm());
    }

    @Test
    void stepToNextEvent_movesToFirstQuestion() {
        int event = formDef.stepToNextEvent();
        assertEquals(FormEntryController.EVENT_QUESTION, event);
    }

    @Test
    void getCurrentPrompt_afterStep_returnsQuestionPrompt() {
        formDef.stepToNextEvent();
        FormEntryPrompt prompt = formDef.getCurrentPrompt();
        assertNotNull(prompt);
        assertEquals("Name", prompt.getQuestionText());
    }

    @Test
    void getCurrentLabel_returnsLabel() {
        formDef.stepToNextEvent();
        assertEquals("Name", formDef.getCurrentLabel());
    }

    @Test
    void getCurrentHint_returnsHint() {
        formDef.stepToNextEvent();
        assertEquals("Enter your name", formDef.getCurrentHint());
    }

    @Test
    void getCurrentDataType_returnsDataType() {
        formDef.stepToNextEvent();
        assertEquals(1, formDef.getCurrentDataType()); // DATATYPE_TEXT
    }

    @Test
    void getCurrentControlType_returnsControlType() {
        formDef.stepToNextEvent();
        assertEquals(1, formDef.getCurrentControlType()); // CONTROL_INPUT
    }

    @Test
    void isCurrentRequired_returnsRequired() {
        formDef.stepToNextEvent();
        assertTrue(formDef.isCurrentRequired());
    }

    @Test
    void isCurrentReadOnly_returnsFalse() {
        formDef.stepToNextEvent();
        assertFalse(formDef.isCurrentReadOnly());
    }

    @Test
    void getCurrentConstraintText_returnsConstraint() {
        formDef.stepToNextEvent(); // name
        formDef.stepToNextEvent(); // age
        assertNotNull(formDef.getCurrentConstraintText());
        assertTrue(formDef.getCurrentConstraintText().contains("> 0"));
    }

    @Test
    void getCurrentAppearanceHint_returnsNull() {
        formDef.stepToNextEvent();
        assertNull(formDef.getCurrentAppearanceHint());
    }

    @Test
    void getCurrentChoices_returnsEmptyForNonSelect() {
        formDef.stepToNextEvent();
        List<FormDefinition.SelectChoice> choices = formDef.getCurrentChoices();
        assertTrue(choices.isEmpty());
    }

    @Test
    void getCurrentChoices_returnsChoicesForSelect() {
        FormDefinition selectDef = FormDefinition.createNew(SELECT_XFORM, "test");
        selectDef.stepToNextEvent();
        List<FormDefinition.SelectChoice> choices = selectDef.getCurrentChoices();
        assertEquals(2, choices.size());
        assertEquals("male", choices.get(0).getValue());
        assertEquals("Male", choices.get(0).getLabel());
        assertEquals("female", choices.get(1).getValue());
        assertEquals("Female", choices.get(1).getLabel());
    }

    @Test
    void getCurrentAnswerText_initiallyNull() {
        formDef.stepToNextEvent();
        assertNull(formDef.getCurrentAnswerText());
    }

    @Test
    void getCurrentQuestionIndex_returnsIndex() {
        formDef.stepToNextEvent();
        FormIndex index = formDef.getCurrentQuestionIndex();
        assertNotNull(index);
    }

    @Test
    void getCurrentQuestionNumber_returnsNumber() {
        formDef.stepToNextEvent();
        int num = formDef.getCurrentQuestionNumber();
        assertEquals(1, num);
    }

    @Test
    void answerQuestion_validAnswer_returnsSuccess() {
        formDef.stepToNextEvent(); // name
        IAnswerData answer = new StringData("John");
        int result = formDef.answerQuestion(answer);
        assertEquals(FormEntryController.ANSWER_OK, result);
    }

    @Test
    void answerQuestion_requiredButEmpty_returnsRequiredError() {
        formDef.stepToNextEvent(); // name (required)
        int result = formDef.answerQuestion(null);
        assertEquals(FormEntryController.ANSWER_REQUIRED_BUT_EMPTY, result);
    }

    @Test
    void answerQuestion_constraintViolated_returnsConstraintError() {
        formDef.stepToNextEvent(); // name
        formDef.answerQuestion(new StringData("John"));
        formDef.stepToNextEvent(); // age
        IAnswerData answer = new StringData("150"); // violates constraint < 120
        int result = formDef.answerQuestion(answer);
        assertEquals(FormEntryController.ANSWER_CONSTRAINT_VIOLATED, result);
    }

    @Test
    void parseAnswer_validString_returnsStringData() {
        formDef.stepToNextEvent(); // name
        IAnswerData answer = formDef.parseAnswer("John");
        assertNotNull(answer);
        assertEquals("John", answer.getDisplayText());
    }

    @Test
    void parseAnswer_emptyString_returnsNull() {
        formDef.stepToNextEvent();
        IAnswerData answer = formDef.parseAnswer("");
        assertNull(answer);
    }

    @Test
    void parseAnswer_null_returnsNull() {
        formDef.stepToNextEvent();
        IAnswerData answer = formDef.parseAnswer(null);
        assertNull(answer);
    }

    @Test
    void finalizeAndSerialize_returnsXml() {
        formDef.stepToNextEvent(); // name
        formDef.answerQuestion(new StringData("John"));
        formDef.stepToNextEvent(); // age
        formDef.answerQuestion(new StringData("30"));
        
        String xml = formDef.finalizeAndSerialize();
        assertNotNull(xml);
        assertTrue(xml.contains("<name>John</name>"));
        assertTrue(xml.contains("<age>30</age>"));
        assertTrue(xml.contains("instanceID"));
    }

    @Test
    void serializeDraft_returnsXmlWithoutFiltering() {
        formDef.stepToNextEvent(); // name
        formDef.answerQuestion(new StringData("John"));
        
        String xml = formDef.serializeDraft();
        assertNotNull(xml);
        assertTrue(xml.contains("<name>John</name>"));
    }

    @Test
    void getInstanceId_returnsInstanceId() {
        String id = formDef.getInstanceId();
        assertNotNull(id);
        assertTrue(id.startsWith("uuid:"));
    }

    @Test
    void setLocale_changesLocale() {
        formDef.setLocale(Locale.FRENCH);
        assertEquals(Locale.FRENCH, formDef.getLocale());
    }

    @Test
    void evaluateXPath_validExpression_returnsResult() {
        formDef.stepToNextEvent(); // name
        formDef.answerQuestion(new StringData("John"));
        
        Object result = formDef.evaluateXPath("/data/name");
        assertEquals("John", result);
    }

    @Test
    void evaluateXPath_invalidExpression_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            formDef.evaluateXPath("invalid xpath [");
        });
    }

    @Test
    void isRelevant_validExpression_returnsBoolean() {
        FormDefinition relevantDef = FormDefinition.createNew(RELEVANT_XFORM, "test");
        relevantDef.stepToNextEvent(); // show_details
        relevantDef.answerQuestion(new org.javarosa.core.model.data.BooleanData(true));
        relevantDef.stepToNextEvent(); // details
        
        boolean relevant = relevantDef.isRelevant("/data/show_details = true()");
        assertTrue(relevant);
    }

    @Test
    void getNode_validXPath_returnsTreeElement() {
        formDef.stepToNextEvent(); // name
        TreeElement node = formDef.getNode("/data/name");
        assertNotNull(node);
        assertEquals("name", node.getName());
    }

    @Test
    void setValue_validNode_setsValue() {
        formDef.stepToNextEvent(); // name
        formDef.setValue("/data/name", new StringData("Jane"));
        
        Object result = formDef.evaluateXPath("/data/name");
        assertEquals("Jane", result);
    }

    @Test
    void getQuestion_validPath_returnsQuestionDef() {
        FormDefinition def = FormDefinition.createNew(SIMPLE_XFORM, "test");
        QuestionDef q = def.getQuestion("name", "", 0);
        assertNotNull(q);
    }

    @Test
    void getCalculations_returnsCalculationQuestions() {
        FormDefinition calcDef = FormDefinition.createNew(CALCULATION_XFORM, "test");
        List<QuestionDef> calculations = calcDef.getCalculations();
        assertEquals(1, calculations.size());
    }

    @Test
    void getRelevantQuestions_returnsRelevantQuestions() {
        FormDefinition relevantDef = FormDefinition.createNew(RELEVANT_XFORM, "test");
        List<QuestionDef> relevant = relevantDef.getRelevantQuestions();
        assertEquals(1, relevant.size()); // details has relevant bind
    }

    @Test
    void getRelevantExpression_returnsExpression() {
        FormDefinition relevantDef = FormDefinition.createNew(RELEVANT_XFORM, "test");
        relevantDef.stepToNextEvent(); // show_details
        relevantDef.stepToNextEvent(); // details
        
        TreeElement element = relevantDef.getNode("/data/details");
        String expr = relevantDef.getRelevantExpression(element);
        assertEquals("/data/show_details = true()", expr);
    }

    @Test
    void getCalculateExpression_returnsExpression() {
        FormDefinition calcDef = FormDefinition.createNew(CALCULATION_XFORM, "test");
        calcDef.stepToNextEvent(); // a
        calcDef.stepToNextEvent(); // b
        calcDef.stepToNextEvent(); // sum
        
        TreeElement element = calcDef.getNode("/data/sum");
        String expr = calcDef.getCalculateExpression(element);
        assertEquals("/data/a + /data/b", expr);
    }

    @Test
    void getConstraintExpression_returnsExpression() {
        formDef.stepToNextEvent(); // name
        formDef.stepToNextEvent(); // age
        
        TreeElement element = formDef.getNode("/data/age");
        String expr = formDef.getConstraintExpression(element);
        assertNotNull(expr);
        assertTrue(expr.contains("> 0"));
    }

    @Test
    void getQuestionPath_returnsName() {
        FormDefinition def = FormDefinition.createNew(SIMPLE_XFORM, "test");
        def.stepToNextEvent(); // name
        FormEntryPrompt prompt = def.getCurrentPrompt();
        QuestionDef qDef = def.findQuestionByRef(def.getFormDef(), prompt.getIndex().getReference());
        
        String path = def.getQuestionPath(qDef);
        assertEquals("name", path);
    }

    @Test
    void getRepeatPath_returnsPath() {
        FormDefinition repeatDef = FormDefinition.createNew(REPEAT_XFORM, "test");
        repeatDef.stepToNextEvent(); // household group
        repeatDef.stepToNextEvent(); // household name
        repeatDef.stepToNextEvent(); // new-repeat prompt
        repeatDef.createNewRepeat(); // repeat group
        repeatDef.stepToNextEvent(); // member name

        FormEntryPrompt prompt = repeatDef.getCurrentPrompt();
        assertNotNull(prompt);

        QuestionDef qDef = repeatDef.findQuestionByRef(
                repeatDef.getFormDef(),
                prompt.getIndex().getReference());

        String path = repeatDef.getRepeatPath(qDef);
        assertTrue(path.contains("household/members/member"));
    }

    @Test
    void getRepeatIndex_returnsZero() {
        FormDefinition def = FormDefinition.createNew(SIMPLE_XFORM, "test");
        def.stepToNextEvent();
        FormEntryPrompt prompt = def.getCurrentPrompt();
        QuestionDef qDef = def.findQuestionByRef(def.getFormDef(), prompt.getIndex().getReference());
        
        Integer index = def.getRepeatIndex(qDef);
        assertEquals(0, index);
    }

    @Test
    void getQuestionTypeString_returnsType() {
        FormDefinition def = FormDefinition.createNew(SIMPLE_XFORM, "test");
        def.stepToNextEvent(); // name
        FormEntryPrompt prompt = def.getCurrentPrompt();
        QuestionDef qDef = def.findQuestionByRef(def.getFormDef(), prompt.getIndex().getReference());
        
        String type = def.getQuestionTypeString(qDef);
        assertEquals("text", type);
    }

    @Test
    void getTreeElement_returnsElement() {
        FormDefinition def = FormDefinition.createNew(SIMPLE_XFORM, "test");
        def.stepToNextEvent(); // name
        FormEntryPrompt prompt = def.getCurrentPrompt();
        QuestionDef qDef = def.findQuestionByRef(def.getFormDef(), prompt.getIndex().getReference());
        
        TreeElement element = def.getTreeElement(qDef);
        assertNotNull(element);
        assertEquals("name", element.getName());
    }

    @Test
    void validate_validForm_returnsValid() {
        formDef.stepToNextEvent(); // name
        formDef.answerQuestion(new StringData("John"));
        formDef.stepToNextEvent(); // age
        formDef.answerQuestion(new StringData("30"));
        
        FormDefinition.ValidationResult result = formDef.validate();
        assertTrue(result.isValid());
    }

    @Test
    void validate_missingRequired_returnsInvalid() {
        formDef.stepToNextEvent(); // name (required)
        // Don't answer
        formDef.stepToNextEvent(); // age
        formDef.answerQuestion(new StringData("30"));
        
        FormDefinition.ValidationResult result = formDef.validate();
        assertFalse(result.isValid());
        assertNotNull(result.getFailedPrompt());
    }

    @Test
    void descendIntoRepeat_works() {
        FormDefinition repeatDef = FormDefinition.createNew(REPEAT_XFORM, "test");
        repeatDef.stepToNextEvent(); // household group
        repeatDef.stepToNextEvent(); // household name
        repeatDef.stepToNextEvent(); // new-repeat prompt

        // First repeat
        repeatDef.createNewRepeat(); // repeat group
        repeatDef.stepToNextEvent(); // member name
        repeatDef.answerQuestion(new StringData("Member 1"));
        repeatDef.stepToNextEvent(); // member age
        repeatDef.answerQuestion(new StringData("10"));

        // Second repeat. createNewRepeat() must navigate from inside repeat 0
        // to the add prompt before creating repeat 1.
        repeatDef.createNewRepeat(); // repeat group
        repeatDef.stepToNextEvent(); // member name
        repeatDef.answerQuestion(new StringData("Member 2"));
        repeatDef.stepToNextEvent(); // member age
        repeatDef.answerQuestion(new StringData("5"));

        // Select the first repeat.
        FormIndex index = repeatDef.descendIntoRepeat(0);
        assertNotNull(index);
        assertEquals(FormEntryController.EVENT_REPEAT, repeatDef.getCurrentEvent());

        repeatDef.stepToNextEvent(); // member name
        FormEntryPrompt prompt = repeatDef.getCurrentPrompt();
        assertNotNull(prompt);
        assertEquals("Member Name", prompt.getQuestionText());
        assertEquals("Member 1", prompt.getAnswerText());
    }

    @Test
    void createNewRepeat_createsInstance() {
        FormDefinition repeatDef = FormDefinition.createNew(REPEAT_XFORM, "test");
        repeatDef.stepToNextEvent(); // household group
        repeatDef.stepToNextEvent(); // household name
        repeatDef.stepToNextEvent(); // new-repeat prompt

        repeatDef.createNewRepeat();

        // JavaRosa's default FormEntryModel uses linear repeat navigation.
        assertEquals(FormEntryController.EVENT_REPEAT, repeatDef.getCurrentEvent());

        int event = repeatDef.stepToNextEvent();
        assertEquals(FormEntryController.EVENT_QUESTION, event);
    }

    @Test
    void deleteRepeat_deletesInstance() {
        FormDefinition repeatDef = FormDefinition.createNew(REPEAT_XFORM, "test");
        repeatDef.stepToNextEvent(); // household group
        repeatDef.stepToNextEvent(); // household name
        repeatDef.stepToNextEvent(); // new-repeat prompt

        repeatDef.createNewRepeat(); // repeat group
        repeatDef.stepToNextEvent(); // member name
        repeatDef.answerQuestion(new StringData("Member 1"));

        repeatDef.deleteRepeat();

        // In JavaRosa's default linear repeat mode, deleting the only repeat
        // returns to the add-new-repeat prompt.
        assertEquals(
                FormEntryController.EVENT_PROMPT_NEW_REPEAT,
                repeatDef.getCurrentEvent());
    }

    @Test
    void jumpToNewRepeatPrompt_jumpsToNewRepeat() {
        FormDefinition repeatDef = FormDefinition.createNew(REPEAT_XFORM, "test");
        repeatDef.stepToNextEvent(); // household group
        repeatDef.stepToNextEvent(); // household name
        repeatDef.stepToNextEvent(); // new-repeat prompt

        repeatDef.jumpToNewRepeatPrompt();

        assertEquals(
                FormEntryController.EVENT_PROMPT_NEW_REPEAT,
                repeatDef.getCurrentEvent());
    }

    @Test
    void newRepeat_withIndex_createsAtIndex() {
        FormDefinition repeatDef = FormDefinition.createNew(REPEAT_XFORM, "test");
        repeatDef.stepToNextEvent(); // household group
        repeatDef.stepToNextEvent(); // household name
        repeatDef.stepToNextEvent(); // new-repeat prompt

        FormIndex repeatIndex = repeatDef.getCurrentFormIndex();
        assertNotNull(repeatIndex);

        repeatDef.newRepeat(repeatIndex);
        assertEquals(FormEntryController.EVENT_REPEAT, repeatDef.getCurrentEvent());

        int event = repeatDef.stepToNextEvent();
        assertEquals(FormEntryController.EVENT_QUESTION, event);
    }

    @Test
    void repeatCount_exposesJavaRosaControlledTarget() {
        FormDefinition repeatDef = FormDefinition.createNew(REPEAT_COUNT_XFORM, "test");

        int event = repeatDef.stepToNextEvent();

        assertEquals(FormEntryController.EVENT_REPEAT, event);
        assertTrue(repeatDef.isCurrentRepeatCountControlled());
        assertEquals(2, repeatDef.getCurrentRepeatTargetCount());
        assertEquals(1, repeatDef.getCurrentRepeatCount());
        assertEquals(0, repeatDef.getCurrentRepeatIndex());
        assertFalse(repeatDef.canAddCurrentRepeat());
        assertFalse(repeatDef.canDeleteCurrentRepeat());
    }

    @Test
    void repeatCount_materializesConfiguredInstancesDuringNavigation() {
        FormDefinition repeatDef = FormDefinition.createNew(REPEAT_COUNT_XFORM, "test");

        assertEquals(FormEntryController.EVENT_REPEAT, repeatDef.stepToNextEvent());
        assertEquals(1, repeatDef.getCurrentRepeatCount());

        assertEquals(FormEntryController.EVENT_QUESTION, repeatDef.stepToNextEvent());
        repeatDef.answerQuestion(new StringData("Member 1"));

        assertEquals(FormEntryController.EVENT_REPEAT, repeatDef.stepToNextEvent());
        assertEquals(2, repeatDef.getCurrentRepeatCount());
        assertEquals(1, repeatDef.getCurrentRepeatIndex());
        assertEquals(2, repeatDef.getCurrentRepeatTargetCount());

        assertEquals(FormEntryController.EVENT_QUESTION, repeatDef.stepToNextEvent());
        repeatDef.answerQuestion(new StringData("Member 2"));

        assertEquals(FormEntryController.EVENT_END_OF_FORM, repeatDef.stepToNextEvent());
    }

    @Test
    void repeatCount_rejectsManualAddAndDelete() {
        FormDefinition repeatDef = FormDefinition.createNew(REPEAT_COUNT_XFORM, "test");
        assertEquals(FormEntryController.EVENT_REPEAT, repeatDef.stepToNextEvent());

        IllegalArgumentException addException = assertThrows(
                IllegalArgumentException.class,
                repeatDef::createNewRepeat);
        assertTrue(addException.getMessage().contains("repeat_count"));

        IllegalArgumentException deleteException = assertThrows(
                IllegalArgumentException.class,
                repeatDef::deleteRepeat);
        assertTrue(deleteException.getMessage().contains("repeat_count"));
    }

    @Test
    void repeatCount_descendIntoMaterializedInstanceWorks() {
        FormDefinition repeatDef = FormDefinition.createNew(REPEAT_COUNT_XFORM, "test");
        repeatDef.stepToNextEvent(); // repeat 0
        repeatDef.stepToNextEvent(); // member 0 name
        repeatDef.answerQuestion(new StringData("Member 1"));
        repeatDef.stepToNextEvent(); // repeat 1, materialized by JavaRosa

        FormIndex firstRepeat = repeatDef.descendIntoRepeat(0);

        assertNotNull(firstRepeat);
        assertEquals(FormEntryController.EVENT_REPEAT, repeatDef.getCurrentEvent());
        assertEquals(0, repeatDef.getCurrentRepeatIndex());
        assertEquals(2, repeatDef.getCurrentRepeatCount());
    }

    @Test
    void stepToPreviousEvent_movesBack() {
        formDef.stepToNextEvent(); // name
        formDef.stepToNextEvent(); // age
        
        int event = formDef.stepToPreviousEvent();
        assertEquals(FormEntryController.EVENT_QUESTION, event);
        assertEquals("Name", formDef.getCurrentLabel());
    }

    @Test
    void isIndexRelevant_checksRelevance() {
        FormDefinition relevantDef = FormDefinition.createNew(RELEVANT_XFORM, "test");

        // Make details relevant first so JavaRosa navigation can expose its
        // FormIndex. stepToNextEvent intentionally skips irrelevant indexes.
        relevantDef.stepToNextEvent(); // show_details
        relevantDef.answerQuestion(
                new org.javarosa.core.model.data.BooleanData(true));
        relevantDef.stepToNextEvent(); // details

        FormIndex detailsIndex = relevantDef.getCurrentQuestionIndex();
        assertNotNull(detailsIndex);

        // Return to trigger question and make details irrelevant.
        relevantDef.stepToPreviousEvent(); // show_details
        relevantDef.answerQuestion(
                new org.javarosa.core.model.data.BooleanData(false));

        assertFalse(relevantDef.isIndexRelevant(detailsIndex));
    }

    @Test
    void repeatCount_moveToQuestionRespectsRepeatIndex() {
        FormDefinition def = FormDefinition.createNew(REPEAT_COUNT_XFORM, "test");

        assertEquals(FormEntryController.EVENT_REPEAT, def.stepToNextEvent());
        assertEquals(FormEntryController.EVENT_QUESTION, def.stepToNextEvent());
        def.answerQuestion(new StringData("Member 1"));

        assertEquals(FormEntryController.EVENT_REPEAT, def.stepToNextEvent());
        assertEquals(FormEntryController.EVENT_QUESTION, def.stepToNextEvent());
        def.answerQuestion(new StringData("Member 2"));

        FormDefinition restored = FormDefinition.createFromInstance(
                REPEAT_COUNT_XFORM,
                def.serializeDraft(),
                "test-restored");

        assertTrue(restored.moveToQuestion(
                "members/member/name",
                "members/member",
                1));
        assertEquals(1, restored.getCurrentRepeatIndex());
        assertEquals("members/member/name", restored.getCurrentQuestionPath());
        assertEquals("members/member", restored.getCurrentRepeatPath());
        assertEquals("Member 2", restored.getCurrentAnswerText());
    }

}