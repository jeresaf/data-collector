package org.odk.platform.service;

import org.javarosa.core.model.FormDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class XFormBodyExtractorTest {

    private XFormBodyExtractor bodyExtractor;
    private JavaRosaFormLoader formLoader;
    private XFormITextExtractor itextExtractor;

    private static final String VALID_XFORM_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Test Form</h:title>
                    <model>
                        <instance>
                            <data id="test_form" version="1.0.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <name/>
                                <age/>
                                <gender/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/name" type="string" required="true()"/>
                        <bind nodeset="/data/age" type="int" required="true()"/>
                        <bind nodeset="/data/gender" type="select1" required="true()"/>
                    </model>
                </h:head>
                <h:body>
                    <input ref="/data/name">
                        <label>Name</label>
                    </input>
                    <input ref="/data/age">
                        <label>Age</label>
                    </input>
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

    private static final String XFORM_WITH_GROUPS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Group Form</h:title>
                    <model>
                        <instance>
                            <data id="group_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <household>
                                    <name/>
                                    <address/>
                                </household>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/household/name" type="string" required="true()"/>
                        <bind nodeset="/data/household/address" type="string"/>
                    </model>
                </h:head>
                <h:body>
                    <group ref="/data/household">
                        <label>Household Information</label>
                        <input ref="/data/household/name">
                            <label>Household Name</label>
                        </input>
                        <input ref="/data/household/address">
                            <label>Address</label>
                        </input>
                    </group>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_REPEATS = """
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
                    <group ref="/data/household">
                        <label>Household</label>
                        <input ref="/data/household/name">
                            <label>Household Name</label>
                        </input>
                        <repeat ref="/data/household/members/member">
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

    private static final String XFORM_WITH_LABELS_AND_HINTS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Labels and Hints Form</h:title>
                    <model>
                        <instance>
                            <data id="labels_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <name/>
                                <age/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/name" type="string" required="true()"/>
                        <bind nodeset="/data/age" type="int" required="true()"/>
                    </model>
                </h:head>
                <h:body>
                    <input ref="/data/name">
                        <label>Full Name</label>
                        <hint>Enter your full legal name</hint>
                    </input>
                    <input ref="/data/age">
                        <label>Age</label>
                        <hint>Enter your age in years</hint>
                    </input>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_APPEARANCE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Appearance Form</h:title>
                    <model>
                        <instance>
                            <data id="appearance_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <name/>
                                <secret/>
                                <location/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/name" type="string" required="true()"/>
                        <bind nodeset="/data/secret" type="string" required="true()"/>
                        <bind nodeset="/data/location" type="geopoint" required="true()"/>
                    </model>
                </h:head>
                <h:body>
                    <input ref="/data/name" appearance="minimal">
                        <label>Name</label>
                    </input>
                    <secret ref="/data/secret" appearance="password">
                        <label>Secret</label>
                    </secret>
                    <input ref="/data/location" appearance="gps">
                        <label>Location</label>
                    </input>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_ITEXT_REFERENCES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms" xmlns:jr="http://openrosa.org/javarosa">
                <h:head>
                    <h:title>IText Form</h:title>
                    <model>
                        <instance>
                            <data id="itext_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <name/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/name" type="string" required="true()"/>
                        <itext>
                            <translation lang="en" default="true">
                                <text id="/data/name:label">
                                    <value>Name (English)</value>
                                </text>
                                <text id="/data/name:hint">
                                    <value>Enter name in English</value>
                                </text>
                            </translation>
                            <translation lang="fr">
                                <text id="/data/name:label">
                                    <value>Nom (Français)</value>
                                </text>
                                <text id="/data/name:hint">
                                    <value>Entrez le nom en français</value>
                                </text>
                            </translation>
                        </itext>
                    </model>
                </h:head>
                <h:body>
                    <input ref="/data/name">
                        <label ref="jr:itext('/data/name:label')"/>
                        <hint ref="jr:itext('/data/name:hint')"/>
                    </input>
                </h:body>
            </h:html>
            """;

    @BeforeEach
    void setUp() {
        formLoader = new JavaRosaFormLoader();
        itextExtractor = new XFormITextExtractor(formLoader);
        bodyExtractor = new XFormBodyExtractor(formLoader, itextExtractor);
    }

    @Test
    void extractBody_withGroups_returnsGroupControls() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_GROUPS);
        XFormBodyExtractor.BodyExtractionResult result = bodyExtractor.extractBody(formDef);

        List<XFormBodyExtractor.ControlInfo> controls = result.getControls();
        
        // Find group
        XFormBodyExtractor.ControlInfo groupControl = controls.stream()
                .filter(e -> e.isGroup())
                .findFirst()
                .orElseThrow();
        assertEquals("group", groupControl.getControlType());
        assertEquals("Household Information", groupControl.getLabel());
        assertEquals("/data/household", groupControl.getXpathReference());
        assertTrue(groupControl.isGroup());
        assertFalse(groupControl.isRepeat());

        // Find name input inside group
        XFormBodyExtractor.ControlInfo nameControl = controls.stream()
                .filter(e -> "/data/household/name".equals(e.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertEquals("input", nameControl.getControlType());
        assertEquals("Household Name", nameControl.getLabel());
    }

    @Test
    void extractBody_validForm_returnsAllControls() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        XFormBodyExtractor.BodyExtractionResult result = bodyExtractor.extractBody(formDef);

        assertNotNull(result);
        assertEquals(3, result.getControlCount());
        List<XFormBodyExtractor.ControlInfo> controls = result.getControls();
        assertEquals(3, controls.size());
    }

    @Test
    void extractBody_validForm_returnsCorrectControlInfo() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        XFormBodyExtractor.BodyExtractionResult result = bodyExtractor.extractBody(formDef);

        List<XFormBodyExtractor.ControlInfo> controls = result.getControls();
        
        // Find name input
        XFormBodyExtractor.ControlInfo nameControl = controls.stream()
                .filter(e -> "/data/name".equals(e.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertEquals("input", nameControl.getControlType());
        assertEquals("Name", nameControl.getLabel());
        assertNull(nameControl.getHint());
        assertNull(nameControl.getAppearance());
        assertFalse(nameControl.isRepeat());
        assertFalse(nameControl.isGroup());

        // Find age input
        XFormBodyExtractor.ControlInfo ageControl = controls.stream()
                .filter(e -> "/data/age".equals(e.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertEquals("input", ageControl.getControlType());
        assertEquals("Age", ageControl.getLabel());

        // Find gender select1
        XFormBodyExtractor.ControlInfo genderControl = controls.stream()
                .filter(e -> "/data/gender".equals(e.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertEquals("select1", genderControl.getControlType());
        assertEquals("Gender", genderControl.getLabel());
        assertEquals(2, genderControl.getChoices().size());
        assertEquals("single", genderControl.getSelectionMode());
    }

    @Test
    void extractBody_withAppearance_returnsAppearance() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_APPEARANCE);
        XFormBodyExtractor.BodyExtractionResult result = bodyExtractor.extractBody(formDef);

        List<XFormBodyExtractor.ControlInfo> controls = result.getControls();
        
        XFormBodyExtractor.ControlInfo nameControl = controls.stream()
                .filter(e -> "/data/name".equals(e.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertEquals("minimal", nameControl.getAppearance());

        XFormBodyExtractor.ControlInfo secretControl = controls.stream()
                .filter(e -> "/data/secret".equals(e.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertEquals("secret", secretControl.getControlType());
        assertEquals("password", secretControl.getAppearance());

        XFormBodyExtractor.ControlInfo locationControl = controls.stream()
                .filter(e -> "/data/location".equals(e.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertEquals("input", locationControl.getControlType());
        assertEquals("gps", locationControl.getAppearance());
    }

    @Test
    void extractBody_withITextReferences_returnsITextRefs() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_ITEXT_REFERENCES);
        
        XFormBodyExtractor.BodyExtractionResult result = bodyExtractor.extractBody(formDef);

        List<XFormBodyExtractor.ControlInfo> controls = result.getControls();
        
        XFormBodyExtractor.ControlInfo nameControl = controls.stream()
                .filter(e -> "/data/name".equals(e.getXpathReference()))
                .findFirst()
                .orElseThrow();
        // Note: The implementation doesn't have labelRef/hintRef, it resolves the label/hint directly
        assertEquals("Name (English)", nameControl.getLabel());
        assertEquals("Enter name in English", nameControl.getHint());
    }

    @Test
    void extractBody_nullFormDef_returnsEmptyResult() {
        XFormBodyExtractor.BodyExtractionResult result = bodyExtractor.extractBody((FormDef) null);
        assertNotNull(result);
        assertEquals(0, result.getControlCount());
    }

    @Test
    void getControlType_returnsCorrectType() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        
        assertEquals("input", bodyExtractor.getControlType(formDef, "/data/name"));
        assertEquals("input", bodyExtractor.getControlType(formDef, "/data/age"));
        assertEquals("select1", bodyExtractor.getControlType(formDef, "/data/gender"));
    }

    @Test
    void getControlType_unknownReference_returnsNull() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        assertNull(bodyExtractor.getControlType(formDef, "/data/unknown"));
    }

    @Test
    void extractBody_withSelectOne_returnsChoices() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        XFormBodyExtractor.BodyExtractionResult result = bodyExtractor.extractBody(formDef);

        List<XFormBodyExtractor.ControlInfo> controls = result.getControls();
        XFormBodyExtractor.ControlInfo genderControl = controls.stream()
                .filter(e -> "/data/gender".equals(e.getXpathReference()))
                .findFirst()
                .orElseThrow();
        
        assertEquals("select1", genderControl.getControlType());
        assertNotNull(genderControl.getChoices());
        assertEquals(2, genderControl.getChoices().size());
        
        XFormBodyExtractor.ChoiceInfo maleChoice = genderControl.getChoices().stream()
                .filter(i -> "male".equals(i.getValue()))
                .findFirst()
                .orElseThrow();
        assertEquals("Male", maleChoice.getLabel());
        
        XFormBodyExtractor.ChoiceInfo femaleChoice = genderControl.getChoices().stream()
                .filter(i -> "female".equals(i.getValue()))
                .findFirst()
                .orElseThrow();
        assertEquals("Female", femaleChoice.getLabel());
    }

    @Test
    void extractBody_withUpload_returnsUploadControl() {
        String xformWithUpload = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>Upload Form</h:title>
                        <model>
                            <instance>
                                <data id="upload_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                    <photo/>
                                    <meta>
                                        <instanceID/>
                                    </meta>
                                </data>
                            </instance>
                            <bind nodeset="/data/photo" type="binary" mediatype="image/*"/>
                        </model>
                    </h:head>
                    <h:body>
                        <upload ref="/data/photo" mediatype="image/*">
                            <label>Photo</label>
                        </upload>
                    </h:body>
                </h:html>
                """;
        
        FormDef formDef = formLoader.loadFormFromXml(xformWithUpload);
        XFormBodyExtractor.BodyExtractionResult result = bodyExtractor.extractBody(formDef);

        List<XFormBodyExtractor.ControlInfo> controls = result.getControls();
        XFormBodyExtractor.ControlInfo photoControl = controls.stream()
                .filter(e -> "/data/photo".equals(e.getXpathReference()))
                .findFirst()
                .orElseThrow();
        
        assertEquals("upload", photoControl.getControlType());
        assertEquals("image/*", photoControl.getMediaType());
    }
}
