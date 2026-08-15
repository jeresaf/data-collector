package org.odk.platform.service;

import org.javarosa.core.model.FormDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FormMetadataExtractorTest {

    private FormMetadataExtractor metadataExtractor;
    private JavaRosaFormLoader formLoader;
    private XFormBindExtractor bindExtractor;
    private XFormBodyExtractor bodyExtractor;
    private XFormChoiceExtractor choiceExtractor;
    private XFormITextExtractor itextExtractor;

    private static final String VALID_XFORM_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms" xmlns:jr="http://openrosa.org/javarosa">
                <h:head>
                    <h:title>Test Form</h:title>
                    <model>
                        <instance>
                            <data id="test_form" version="1.0.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <name/>
                                <age/>
                                <email/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/name" type="string" required="true()"/>
                        <bind nodeset="/data/age" type="int" constraint=". &gt; 0 and . &lt; 120" required="true()"/>
                        <bind nodeset="/data/email" type="string" constraint="regex(., '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}')" required="false()"/>
                    </model>
                    <itext>
                        <translation lang="en" default="true">
                            <text id="/data/name:label">
                                <value>Name</value>
                            </text>
                            <text id="/data/name:hint">
                                <value>Enter your full name</value>
                            </text>
                            <text id="/data/age:label">
                                <value>Age</value>
                            </text>
                            <text id="/data/age:hint">
                                <value>Enter your age in years</value>
                            </text>
                            <text id="/data/email:label">
                                <value>Email</value>
                            </text>
                        </translation>
                    </itext>
                </h:head>
                <h:body>
                    <group ref="/data">
                        <input ref="/data/name">
                            <label ref="jr:itext('/data/name:label')"/>
                            <hint ref="jr:itext('/data/name:hint')"/>
                        </input>
                        <input ref="/data/age">
                            <label ref="jr:itext('/data/age:label')"/>
                            <hint ref="jr:itext('/data/age:hint')"/>
                        </input>
                        <input ref="/data/email">
                            <label ref="jr:itext('/data/email:label')"/>
                        </input>
                    </group>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_MEDIA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms" xmlns:jr="http://openrosa.org/javarosa">
                <h:head>
                    <h:title>Media Form</h:title>
                    <model>
                        <instance>
                            <data id="media_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <photo/>
                                <audio/>
                                <video/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/photo" type="binary"/>
                        <bind nodeset="/data/audio" type="binary"/>
                        <bind nodeset="/data/video" type="binary"/>
                    </model>
                </h:head>
                <h:body>
                    <group ref="/data">
                        <upload ref="/data/photo" mediatype="image/*">
                            <label>Photo</label>
                        </upload>
                        <upload ref="/data/audio" mediatype="audio/*">
                            <label>Audio</label>
                        </upload>
                        <upload ref="/data/video" mediatype="video/*">
                            <label>Video</label>
                        </upload>
                    </group>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_GROUPS_AND_REPEATS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms" xmlns:jr="http://openrosa.org/javarosa">
                <h:head>
                    <h:title>Groups and Repeats Form</h:title>
                    <model>
                        <instance>
                            <data id="groups_repeats_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <household>
                                    <name/>
                                    <members>
                                        <member>
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
                        <bind nodeset="/data/household/members/member/name" type="string"/>
                        <bind nodeset="/data/household/members/member/age" type="int"/>
                    </model>
                </h:head>
                <h:body>
                    <group ref="/data">
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
                    </group>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_CHOICES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms" xmlns:jr="http://openrosa.org/javarosa">
                <h:head>
                    <h:title>Choices Form</h:title>
                    <model>
                        <instance>
                            <data id="choices_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <gender/>
                                <colors/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/gender" type="select1" required="true()"/>
                        <bind nodeset="/data/colors" type="select" required="true()"/>
                    </model>
                    <itext>
                        <translation lang="en" default="true">
                            <text id="/data/gender:label">
                                <value>Gender</value>
                            </text>
                            <text id="/data/gender:option0">
                                <value>Male</value>
                            </text>
                            <text id="/data/gender:option1">
                                <value>Female</value>
                            </text>
                            <text id="/data/colors:label">
                                <value>Colors</value>
                            </text>
                            <text id="/data/colors:option0">
                                <value>Red</value>
                            </text>
                            <text id="/data/colors:option1">
                                <value>Blue</value>
                            </text>
                        </translation>
                    </itext>
                </h:head>
                <h:body>
                    <select1 ref="/data/gender">
                        <label ref="jr:itext('/data/gender:label')"/>
                        <item>
                            <label ref="jr:itext('/data/gender:option0')"/>
                            <value>male</value>
                        </item>
                        <item>
                            <label ref="jr:itext('/data/gender:option1')"/>
                            <value>female</value>
                        </item>
                    </select1>
                    <select ref="/data/colors">
                        <label ref="jr:itext('/data/colors:label')"/>
                        <item>
                            <label ref="jr:itext('/data/colors:option0')"/>
                            <value>red</value>
                        </item>
                        <item>
                            <label ref="jr:itext('/data/colors:option1')"/>
                            <value>blue</value>
                        </item>
                    </select>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_RELEVANT_AND_CALCULATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms" xmlns:jr="http://openrosa.org/javarosa">
                <h:head>
                    <h:title>Relevant and Calculate Form</h:title>
                    <model>
                        <instance>
                            <data id="relevant_calc_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <has_children/>
                                <num_children/>
                                <price/>
                                <quantity/>
                                <total/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/has_children" type="boolean" required="true()"/>
                        <bind nodeset="/data/num_children" type="int" relevant="/data/has_children = true()"/>
                        <bind nodeset="/data/price" type="decimal" required="true()"/>
                        <bind nodeset="/data/quantity" type="int" required="true()"/>
                        <bind nodeset="/data/total" type="decimal" calculate="/data/price * /data/quantity" readonly="true()"/>
                    </model>
                </h:head>
                <h:body>
                    <group ref="/data">
                        <input ref="/data/has_children">
                            <label>Has Children</label>
                        </input>
                        <input ref="/data/num_children">
                            <label>Number of Children</label>
                        </input>
                        <input ref="/data/price">
                            <label>Price</label>
                        </input>
                        <input ref="/data/quantity">
                            <label>Quantity</label>
                        </input>
                        <output ref="/data/total">
                            <label>Total</label>
                        </output>
                    </group>
                </h:body>
            </h:html>
            """;

    @BeforeEach
    void setUp() {
        formLoader = new JavaRosaFormLoader();
        bindExtractor = new XFormBindExtractor(formLoader);
        itextExtractor = new XFormITextExtractor(formLoader);
        bodyExtractor = new XFormBodyExtractor(formLoader, itextExtractor);
        choiceExtractor = new XFormChoiceExtractor(formLoader);
        metadataExtractor = new FormMetadataExtractor(formLoader, bindExtractor, bodyExtractor, choiceExtractor, itextExtractor);
    }

    @Test
    void extractMetadata_validForm_returnsCompleteMetadata() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        FormMetadataExtractor.FormMetadata metadata = metadataExtractor.extractMetadata(formDef);

        assertNotNull(metadata);
        assertEquals("test_form", metadata.getFormId());
        assertEquals("1.0.0", metadata.getVersion());
        assertEquals("Test Form", metadata.getTitle());
        assertEquals(3, metadata.getFieldCount());
        assertEquals(0, metadata.getMediaFieldCount());
        assertEquals(1, metadata.getGroupCount());
        assertEquals(0, metadata.getRepeatCount());
        assertEquals(1, metadata.getLanguageCount());
        assertEquals("en", metadata.getDefaultLanguage());
    }

    @Test
    void extractMetadata_validForm_returnsFieldDetails() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        FormMetadataExtractor.FormMetadata metadata = metadataExtractor.extractMetadata(formDef);

        List<FormMetadataExtractor.FieldInfo> fields = metadata.getFields();
        assertEquals(3, fields.size());

        FormMetadataExtractor.FieldInfo nameField = fields.stream()
                .filter(f -> "/data/name".equals(f.getXpath()))
                .findFirst()
                .orElseThrow();
        assertEquals("string", nameField.getType());
        assertTrue(nameField.isRequired());
        assertEquals("Name", nameField.getLabel());
        assertEquals("Enter your full name", nameField.getHint());
        assertFalse(nameField.isMedia());
        assertFalse(nameField.isCalculated());
        assertFalse(nameField.isReadOnly());

        FormMetadataExtractor.FieldInfo ageField = fields.stream()
                .filter(f -> "/data/age".equals(f.getXpath()))
                .findFirst()
                .orElseThrow();
        assertEquals("int", ageField.getType());
        assertTrue(ageField.isRequired());
        assertEquals("Age", ageField.getLabel());
        assertEquals("Enter your age in years", ageField.getHint());
        assertNotNull(ageField.getConstraint());
        assertTrue(ageField.getConstraint().contains("> 0"));
    }

    @Test
    void extractMetadata_withMedia_returnsMediaFields() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_MEDIA);
        FormMetadataExtractor.FormMetadata metadata = metadataExtractor.extractMetadata(formDef);

        assertEquals(3, metadata.getMediaFieldCount());
        List<FormMetadataExtractor.FieldInfo> fields = metadata.getFields();
        
        FormMetadataExtractor.FieldInfo photoField = fields.stream()
                .filter(f -> "/data/photo".equals(f.getXpath()))
                .findFirst()
                .orElseThrow();
        assertTrue(photoField.isMedia());
        assertEquals("image/*", photoField.getMediaType());
        assertEquals("binary", photoField.getType());

        FormMetadataExtractor.FieldInfo audioField = fields.stream()
                .filter(f -> "/data/audio".equals(f.getXpath()))
                .findFirst()
                .orElseThrow();
        assertTrue(audioField.isMedia());
        assertEquals("audio/*", audioField.getMediaType());

        FormMetadataExtractor.FieldInfo videoField = fields.stream()
                .filter(f -> "/data/video".equals(f.getXpath()))
                .findFirst()
                .orElseThrow();
        assertTrue(videoField.isMedia());
        assertEquals("video/*", videoField.getMediaType());
    }

    @Test
    void extractMetadata_withGroupsAndRepeats_returnsGroupAndRepeatCounts() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_GROUPS_AND_REPEATS);
        FormMetadataExtractor.FormMetadata metadata = metadataExtractor.extractMetadata(formDef);

        assertEquals(1, metadata.getGroupCount());
        assertEquals(1, metadata.getRepeatCount());
        assertEquals(3, metadata.getFieldCount());
        assertEquals(3, metadata.getFields().stream()
                .map(FormMetadataExtractor.FieldInfo::getXpath)
                .distinct()
                .count());
    }

    @Test
    void extractMetadata_withChoices_returnsChoiceLists() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_CHOICES);
        FormMetadataExtractor.FormMetadata metadata = metadataExtractor.extractMetadata(formDef);

        assertEquals(2, metadata.getChoiceListCount());
        List<FormMetadataExtractor.ChoiceListInfo> choiceLists = metadata.getChoiceLists();
        
        FormMetadataExtractor.ChoiceListInfo genderList = choiceLists.stream()
                .filter(cl -> "/data/gender".equals(cl.getXpath()))
                .findFirst()
                .orElseThrow();
        assertEquals("select1", genderList.getSelectType());
        assertEquals(2, genderList.getChoiceCount());
        
        FormMetadataExtractor.ChoiceListInfo colorsList = choiceLists.stream()
                .filter(cl -> "/data/colors".equals(cl.getXpath()))
                .findFirst()
                .orElseThrow();
        assertEquals("select", colorsList.getSelectType());
        assertEquals(2, colorsList.getChoiceCount());
    }

    @Test
    void extractMetadata_withRelevantAndCalculate_returnsRelevantAndCalculatedFields() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_RELEVANT_AND_CALCULATE);
        FormMetadataExtractor.FormMetadata metadata = metadataExtractor.extractMetadata(formDef);

        List<FormMetadataExtractor.FieldInfo> fields = metadata.getFields();
        
        FormMetadataExtractor.FieldInfo numChildrenField = fields.stream()
                .filter(f -> "/data/num_children".equals(f.getXpath()))
                .findFirst()
                .orElseThrow();
        assertNotNull(numChildrenField.getRelevant());
        assertEquals("/data/has_children = true()", numChildrenField.getRelevant());

        FormMetadataExtractor.FieldInfo totalField = fields.stream()
                .filter(f -> "/data/total".equals(f.getXpath()))
                .findFirst()
                .orElseThrow();
        assertTrue(totalField.isCalculated());
        assertEquals("/data/price * /data/quantity", totalField.getCalculate());
        assertTrue(totalField.isReadOnly());
    }

    @Test
    void extractMetadata_withExplicitCalculatedBind_keepsCalculatedFieldAndExcludesContainers() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_RELEVANT_AND_CALCULATE);
        FormMetadataExtractor.FormMetadata metadata = metadataExtractor.extractMetadata(formDef);

        assertEquals(5, metadata.getFieldCount());
        assertTrue(metadata.getFields().stream()
                .anyMatch(field -> "/data/total".equals(field.getXpath()) && field.isCalculated()));
    }

    @Test
    void extractMetadata_withIText_returnsLanguages() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        FormMetadataExtractor.FormMetadata metadata = metadataExtractor.extractMetadata(formDef);

        assertEquals(1, metadata.getLanguageCount());
        assertEquals("en", metadata.getDefaultLanguage());
        assertTrue(metadata.getLanguages().contains("en"));
    }

    @Test
    void extractMetadata_nullFormDef_returnsEmptyMetadata() {
        FormMetadataExtractor.FormMetadata metadata = metadataExtractor.extractMetadata((FormDef) null);
        assertNotNull(metadata);
        assertEquals(0, metadata.getFieldCount());
        assertNull(metadata.getFormId());
        assertNull(metadata.getVersion());
    }

    @Test
    void extractMetadataFromXml_validXml_returnsMetadata() {
        FormMetadataExtractor.FormMetadataResult metadata = metadataExtractor.extractMetadataFromXml(VALID_XFORM_XML);

        assertNotNull(metadata);
        assertEquals("test_form", metadata.getFormId());
        assertEquals("1.0.0", metadata.getVersion());
        assertEquals("Test Form", metadata.getTitle());
    }

    @Test
    void extractMetadataFromXml_invalidXml_throwsException() {
        String invalidXml = "<invalid>xml</invalid>";
        assertThrows(IllegalArgumentException.class, () -> {
            metadataExtractor.extractMetadataFromXml(invalidXml);
        });
    }

    @Test
    void getFormTitle_returnsTitle() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        String title = metadataExtractor.getFormTitle(formDef);
        assertEquals("Test Form", title);
    }

    @Test
    void getFormId_returnsFormId() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        String formId = metadataExtractor.getFormId(formDef);
        assertEquals("test_form", formId);
    }

    @Test
    void getFormVersion_returnsVersion() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        String version = metadataExtractor.getFormVersion(formDef);
        assertEquals("1.0.0", version);
    }

    @Test
    void getFieldCount_returnsCorrectCount() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        int count = metadataExtractor.getFieldCount(formDef);
        assertEquals(3, count);
    }

    @Test
    void getMediaFieldCount_returnsCorrectCount() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_MEDIA);
        int count = metadataExtractor.getMediaFieldCount(formDef);
        assertEquals(3, count);
    }

    @Test
    void hasMediaFields_returnsTrueForMediaForm() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_MEDIA);
        assertTrue(metadataExtractor.hasMediaFields(formDef));
    }

    @Test
    void hasMediaFields_returnsFalseForNonMediaForm() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        assertFalse(metadataExtractor.hasMediaFields(formDef));
    }

    @Test
    void getLanguages_returnsAllLanguages() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        List<String> languages = metadataExtractor.getLanguages(formDef);
        assertNotNull(languages);
        assertEquals(1, languages.size());
        assertTrue(languages.contains("en"));
    }

    @Test
    void getDefaultLanguage_returnsDefaultLanguage() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        String defaultLang = metadataExtractor.getDefaultLanguage(formDef);
        assertEquals("en", defaultLang);
    }
}