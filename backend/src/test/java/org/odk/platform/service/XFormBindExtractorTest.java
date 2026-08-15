package org.odk.platform.service;

import org.javarosa.core.model.FormDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class XFormBindExtractorTest {

    private XFormBindExtractor bindExtractor;
    private JavaRosaFormLoader formLoader;

    private static final String VALID_XFORM_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns:jr="http://openrosa.org/javarosa" xmlns="http://www.w3.org/2002/xforms">
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
                </h:head>
                <h:body>
                    <group ref="/data">
                        <input ref="/data/name">
                            <label>Name</label>
                        </input>
                        <input ref="/data/age">
                            <label>Age</label>
                        </input>
                        <input ref="/data/email">
                            <label>Email</label>
                        </input>
                    </group>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_RELEVANT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns:jr="http://openrosa.org/javarosa" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Relevant Form</h:title>
                    <model>
                        <instance>
                            <data id="relevant_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <has_children/>
                                <num_children/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/has_children" type="boolean" required="true()"/>
                        <bind nodeset="/data/num_children" type="int" relevant="/data/has_children = true()"/>
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
                    </group>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_CALCULATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns:jr="http://openrosa.org/javarosa" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Calculate Form</h:title>
                    <model>
                        <instance>
                            <data id="calculate_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <price/>
                                <quantity/>
                                <total/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/price" type="decimal" required="true()"/>
                        <bind nodeset="/data/quantity" type="int" required="true()"/>
                        <bind nodeset="/data/total" type="decimal" calculate="/data/price * /data/quantity"/>
                    </model>
                </h:head>
                <h:body>
                    <group ref="/data">
                        <input ref="/data/price">
                            <label>Price</label>
                        </input>
                        <input ref="/data/quantity">
                            <label>Quantity</label>
                        </input>
                    </group>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_MEDIA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns:jr="http://openrosa.org/javarosa" xmlns="http://www.w3.org/2002/xforms">
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

    private static final String XFORM_WITH_READONLY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns:jr="http://openrosa.org/javarosa" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Readonly Form</h:title>
                    <model>
                        <instance>
                            <data id="readonly_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <auto_calc/>
                                <user_input/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/auto_calc" type="decimal" calculate="/data/user_input * 2" readonly="true()"/>
                        <bind nodeset="/data/user_input" type="int" required="true()"/>
                    </model>
                </h:head>
                <h:body>
                    <group ref="/data">
                        <input ref="/data/user_input">
                            <label>User Input</label>
                        </input>
                    </group>
                </h:body>
            </h:html>
            """;

    @BeforeEach
    void setUp() {
        formLoader = new JavaRosaFormLoader();
        bindExtractor = new XFormBindExtractor(formLoader);
    }

    @Test
    void extractBinds_validForm_returnsAllBinds() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        XFormBindExtractor.BindExtractionResult result = bindExtractor.extractBinds(formDef);

        assertNotNull(result);
        assertEquals(3, result.getBindCount());
        List<XFormBindExtractor.BindInfo> binds = result.getBinds();
        assertEquals(3, binds.size());
    }

    @Test
    void extractBinds_validForm_returnsCorrectBindInfo() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        XFormBindExtractor.BindExtractionResult result = bindExtractor.extractBinds(formDef);

        List<XFormBindExtractor.BindInfo> binds = result.getBinds();
        
        // Find name bind
        XFormBindExtractor.BindInfo nameBind = binds.stream()
                .filter(b -> "/data/name".equals(b.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertEquals("string", nameBind.getDataType());
        assertTrue(nameBind.hasRequired());
        assertNull(nameBind.getConstraint());
        assertNull(nameBind.getRelevant());
        assertNull(nameBind.getCalculate());
        assertFalse(nameBind.hasReadOnly());

        // Find age bind
        XFormBindExtractor.BindInfo ageBind = binds.stream()
                .filter(b -> "/data/age".equals(b.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertEquals("int", ageBind.getDataType());
        assertTrue(ageBind.hasRequired());
        assertEquals(". > 0 and . < 120", ageBind.getConstraint());
        assertNull(ageBind.getRelevant());
        assertNull(ageBind.getCalculate());
        assertFalse(ageBind.hasReadOnly());

        // Find email bind
        XFormBindExtractor.BindInfo emailBind = binds.stream()
                .filter(b -> "/data/email".equals(b.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertEquals("string", emailBind.getDataType());
        assertFalse(emailBind.hasRequired());
        assertNotNull(emailBind.getConstraint());
        assertTrue(emailBind.getConstraint().contains("regex"));
    }

    @Test
    void extractBinds_withRelevant_returnsRelevantExpression() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_RELEVANT);
        XFormBindExtractor.BindExtractionResult result = bindExtractor.extractBinds(formDef);

        List<XFormBindExtractor.BindInfo> binds = result.getBinds();
        XFormBindExtractor.BindInfo numChildrenBind = binds.stream()
                .filter(b -> "/data/num_children".equals(b.getXpathReference()))
                .findFirst()
                .orElseThrow();
        
        assertEquals("/data/has_children = true()", numChildrenBind.getRelevant());
        assertFalse(numChildrenBind.hasRequired());
    }

    @Test
    void extractBinds_withCalculate_returnsCalculateExpression() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_CALCULATE);
        XFormBindExtractor.BindExtractionResult result = bindExtractor.extractBinds(formDef);

        List<XFormBindExtractor.BindInfo> binds = result.getBinds();
        XFormBindExtractor.BindInfo totalBind = binds.stream()
                .filter(b -> "/data/total".equals(b.getXpathReference()))
                .findFirst()
                .orElseThrow();
        
        assertEquals("/data/price * /data/quantity", totalBind.getCalculate());
        assertEquals("decimal", totalBind.getDataType());
    }

    @Test
    void extractBinds_withMedia_returnsAttachmentInfo() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_MEDIA);
        XFormBindExtractor.BindExtractionResult result = bindExtractor.extractBinds(formDef);

        List<XFormBindExtractor.BindInfo> binds = result.getBinds();
        
        XFormBindExtractor.BindInfo photoBind = binds.stream()
                .filter(b -> "/data/photo".equals(b.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertTrue(photoBind.isAttachment());
        assertEquals("image/*", photoBind.getAttachmentMediaType());
        assertEquals("binary", photoBind.getDataType());

        XFormBindExtractor.BindInfo audioBind = binds.stream()
                .filter(b -> "/data/audio".equals(b.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertTrue(audioBind.isAttachment());
        assertEquals("audio/*", audioBind.getAttachmentMediaType());

        XFormBindExtractor.BindInfo videoBind = binds.stream()
                .filter(b -> "/data/video".equals(b.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertTrue(videoBind.isAttachment());
        assertEquals("video/*", videoBind.getAttachmentMediaType());
    }

    @Test
    void extractBinds_withReadonly_returnsReadonlyFlag() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_READONLY);
        XFormBindExtractor.BindExtractionResult result = bindExtractor.extractBinds(formDef);

        List<XFormBindExtractor.BindInfo> binds = result.getBinds();
        XFormBindExtractor.BindInfo autoCalcBind = binds.stream()
                .filter(b -> "/data/auto_calc".equals(b.getXpathReference()))
                .findFirst()
                .orElseThrow();
        
        assertTrue(autoCalcBind.hasReadOnly());
        assertNotNull(autoCalcBind.getCalculate());
    }

    @Test
    void extractBinds_nullFormDef_returnsEmptyResult() {
        XFormBindExtractor.BindExtractionResult result = bindExtractor.extractBinds((FormDef) null);
        assertNotNull(result);
        assertEquals(0, result.getBindCount());
    }

    @Test
    void getBindAttributes_returnsOriginalAttributes() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        Map<String, String> attributes = bindExtractor.getBindAttributes(formDef, "/data/age");

        assertNotNull(attributes);
        assertEquals("int", attributes.get("type"));
        assertEquals(". > 0 and . < 120", attributes.get("constraint"));
        assertEquals("true()", attributes.get("required"));
    }

    @Test
    void getBindAttributes_unknownReference_returnsEmptyMap() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        Map<String, String> attributes = bindExtractor.getBindAttributes(formDef, "/data/unknown");
        assertNotNull(attributes);
        assertTrue(attributes.isEmpty());
    }

    @Test
    void extractBinds_withConstraintMessage_returnsMessage() {
        String xformWithConstraintMessage = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns:jr="http://openrosa.org/javarosa" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>Constraint Message Form</h:title>
                        <model>
                            <instance>
                                <data id="constraint_msg_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                    <age/>
                                    <meta>
                                        <instanceID/>
                                    </meta>
                                </data>
                            </instance>
                            <bind nodeset="/data/age" type="int" constraint=". &gt; 0 and . &lt; 120" jr:constraintMsg="Age must be between 1 and 119"/>
                        </model>
                    </h:head>
                    <h:body>
                        <group ref="/data">
                            <input ref="/data/age">
                                <label>Age</label>
                            </input>
                        </group>
                    </h:body>
                </h:html>
                """;
        
        FormDef formDef = formLoader.loadFormFromXml(xformWithConstraintMessage);
        XFormBindExtractor.BindExtractionResult result = bindExtractor.extractBinds(formDef);

        List<XFormBindExtractor.BindInfo> binds = result.getBinds();
        XFormBindExtractor.BindInfo ageBind = binds.stream()
                .filter(b -> "/data/age".equals(b.getXpathReference()))
                .findFirst()
                .orElseThrow();
        
        assertEquals("Age must be between 1 and 119", ageBind.getConstraintMessage());
    }
}