package org.odk.platform.service;

import org.javarosa.core.model.FormDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class XFormChoiceExtractorTest {

    private XFormChoiceExtractor choiceExtractor;
    private JavaRosaFormLoader formLoader;

    private static final String VALID_XFORM_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Test Form</h:title>
                    <model>
                        <instance>
                            <data id="test_form" version="1.0.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <gender/>
                                <colors/>
                                <country/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/gender" type="select1" required="true()"/>
                        <bind nodeset="/data/colors" type="select" required="true()"/>
                        <bind nodeset="/data/country" type="select1" required="true()"/>
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
                        <item>
                            <label>Other</label>
                            <value>other</value>
                        </item>
                    </select1>
                    <select ref="/data/colors">
                        <label>Favorite Colors</label>
                        <item>
                            <label>Red</label>
                            <value>red</value>
                        </item>
                        <item>
                            <label>Blue</label>
                            <value>blue</value>
                        </item>
                        <item>
                            <label>Green</label>
                            <value>green</value>
                        </item>
                    </select>
                    <select1 ref="/data/country">
                        <label>Country</label>
                        <item>
                            <label>United States</label>
                            <value>us</value>
                        </item>
                        <item>
                            <label>Canada</label>
                            <value>ca</value>
                        </item>
                    </select1>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_ITEXT_CHOICES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms" xmlns:jr="http://openrosa.org/javarosa">
                <h:head>
                    <h:title>IText Choices Form</h:title>
                    <model>
                        <instance>
                            <data id="itext_choices_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <language/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/language" type="select1" required="true()"/>
                    </model>
                    <itext>
                        <translation lang="en" default="true">
                            <text id="/data/language:label">
                                <value>Language</value>
                            </text>
                            <text id="/data/language:option0">
                                <value>English</value>
                            </text>
                            <text id="/data/language:option1">
                                <value>Spanish</value>
                            </text>
                            <text id="/data/language:option2">
                                <value>French</value>
                            </text>
                        </translation>
                        <translation lang="es">
                            <text id="/data/language:label">
                                <value>Idioma</value>
                            </text>
                            <text id="/data/language:option0">
                                <value>Inglés</value>
                            </text>
                            <text id="/data/language:option1">
                                <value>Español</value>
                            </text>
                            <text id="/data/language:option2">
                                <value>Francés</value>
                            </text>
                        </translation>
                    </itext>
                </h:head>
                <h:body>
                    <select1 ref="/data/language">
                        <label ref="jr:itext('/data/language:label')"/>
                        <item>
                            <label ref="jr:itext('/data/language:option0')"/>
                            <value>en</value>
                        </item>
                        <item>
                            <label ref="jr:itext('/data/language:option1')"/>
                            <value>es</value>
                        </item>
                        <item>
                            <label ref="jr:itext('/data/language:option2')"/>
                            <value>fr</value>
                        </item>
                    </select1>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_IMAGE_CHOICES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Image Choices Form</h:title>
                    <model>
                        <instance>
                            <data id="image_choices_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <fruit/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/fruit" type="select1" required="true()"/>
                    </model>
                </h:head>
                <h:body>
                    <select1 ref="/data/fruit" appearance="image">
                        <label>Select a Fruit</label>
                        <item>
                            <label>Apple</label>
                            <value>apple</value>
                            <image>jr://images/apple.png</image>
                        </item>
                        <item>
                            <label>Banana</label>
                            <value>banana</value>
                            <image>jr://images/banana.png</image>
                        </item>
                        <item>
                            <label>Orange</label>
                            <value>orange</value>
                            <image>jr://images/orange.png</image>
                        </item>
                    </select1>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_AUDIO_CHOICES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Audio Choices Form</h:title>
                    <model>
                        <instance>
                            <data id="audio_choices_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <sound/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/sound" type="select1" required="true()"/>
                    </model>
                </h:head>
                <h:body>
                    <select1 ref="/data/sound" appearance="audio">
                        <label>Select a Sound</label>
                        <item>
                            <label>Dog Bark</label>
                            <value>dog</value>
                            <audio>jr://audio/dog.mp3</audio>
                        </item>
                        <item>
                            <label>Cat Meow</label>
                            <value>cat</value>
                            <audio>jr://audio/cat.mp3</audio>
                        </item>
                    </select1>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_NESTED_CHOICES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Nested Choices Form</h:title>
                    <model>
                        <instance>
                            <data id="nested_choices_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <region/>
                                <district/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/region" type="select1" required="true()"/>
                        <bind nodeset="/data/district" type="select1" required="true()"/>
                    </model>
                </h:head>
                <h:body>
                    <select1 ref="/data/region">
                        <label>Region</label>
                        <item>
                            <label>North</label>
                            <value>north</value>
                        </item>
                        <item>
                            <label>South</label>
                            <value>south</value>
                        </item>
                    </select1>
                    <select1 ref="/data/district">
                        <label>District</label>
                        <item>
                            <label>District A</label>
                            <value>district_a</value>
                        </item>
                        <item>
                            <label>District B</label>
                            <value>district_b</value>
                        </item>
                    </select1>
                </h:body>
            </h:html>
            """;

    @BeforeEach
    void setUp() {
        formLoader = new JavaRosaFormLoader();
        choiceExtractor = new XFormChoiceExtractor(formLoader);
    }

    @Test
    void extractChoices_validForm_returnsAllChoiceLists() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        XFormChoiceExtractor.ChoiceExtractionResult result = choiceExtractor.extractChoices(formDef);

        assertNotNull(result);
        assertEquals(3, result.getChoiceListCount());
        List<XFormChoiceExtractor.ChoiceList> choiceLists = result.getChoiceLists();
        assertEquals(3, choiceLists.size());
    }

    @Test
    void extractChoices_validForm_returnsCorrectChoiceListInfo() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        XFormChoiceExtractor.ChoiceExtractionResult result = choiceExtractor.extractChoices(formDef);

        List<XFormChoiceExtractor.ChoiceList> choiceLists = result.getChoiceLists();
        
        // Find gender choice list
        XFormChoiceExtractor.ChoiceList genderList = choiceLists.stream()
                .filter(cl -> "/data/gender".equals(cl.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertEquals("select1", genderList.getSelectType());
        assertEquals(3, genderList.getChoices().size());
        
        XFormChoiceExtractor.Choice maleChoice = genderList.getChoices().stream()
                .filter(c -> "male".equals(c.getValue()))
                .findFirst()
                .orElseThrow();
        assertEquals("Male", maleChoice.getLabel());
        assertNull(maleChoice.getImageRef());
        assertNull(maleChoice.getAudioRef());

        // Find colors choice list
        XFormChoiceExtractor.ChoiceList colorsList = choiceLists.stream()
                .filter(cl -> "/data/colors".equals(cl.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertEquals("select", colorsList.getSelectType());
        assertEquals(3, colorsList.getChoices().size());

        // Find country choice list
        XFormChoiceExtractor.ChoiceList countryList = choiceLists.stream()
                .filter(cl -> "/data/country".equals(cl.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertEquals("select1", countryList.getSelectType());
        assertEquals(2, countryList.getChoices().size());
    }

    @Test
    void extractChoices_withITextReferences_returnsITextRefs() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_ITEXT_CHOICES);
        XFormChoiceExtractor.ChoiceExtractionResult result = choiceExtractor.extractChoices(formDef);

        List<XFormChoiceExtractor.ChoiceList> choiceLists = result.getChoiceLists();
        XFormChoiceExtractor.ChoiceList languageList = choiceLists.stream()
                .filter(cl -> "/data/language".equals(cl.getXpathReference()))
                .findFirst()
                .orElseThrow();
        
        assertEquals(3, languageList.getChoices().size());
        
        XFormChoiceExtractor.Choice englishChoice = languageList.getChoices().stream()
                .filter(c -> "en".equals(c.getValue()))
                .findFirst()
                .orElseThrow();
        assertEquals("jr:itext('/data/language:option0')", englishChoice.getLabelRef());
        
        XFormChoiceExtractor.Choice spanishChoice = languageList.getChoices().stream()
                .filter(c -> "es".equals(c.getValue()))
                .findFirst()
                .orElseThrow();
        assertEquals("jr:itext('/data/language:option1')", spanishChoice.getLabelRef());
    }

    @Test
    void extractChoices_withImageChoices_returnsImageRefs() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_IMAGE_CHOICES);
        XFormChoiceExtractor.ChoiceExtractionResult result = choiceExtractor.extractChoices(formDef);

        List<XFormChoiceExtractor.ChoiceList> choiceLists = result.getChoiceLists();
        XFormChoiceExtractor.ChoiceList fruitList = choiceLists.stream()
                .filter(cl -> "/data/fruit".equals(cl.getXpathReference()))
                .findFirst()
                .orElseThrow();
        
        assertEquals(3, fruitList.getChoices().size());
        
        XFormChoiceExtractor.Choice appleChoice = fruitList.getChoices().stream()
                .filter(c -> "apple".equals(c.getValue()))
                .findFirst()
                .orElseThrow();
        assertEquals("Apple", appleChoice.getLabel());
        assertEquals("jr://images/apple.png", appleChoice.getImageRef());
        
        XFormChoiceExtractor.Choice bananaChoice = fruitList.getChoices().stream()
                .filter(c -> "banana".equals(c.getValue()))
                .findFirst()
                .orElseThrow();
        assertEquals("jr://images/banana.png", bananaChoice.getImageRef());
    }

    @Test
    void extractChoices_withAudioChoices_returnsAudioRefs() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_AUDIO_CHOICES);
        XFormChoiceExtractor.ChoiceExtractionResult result = choiceExtractor.extractChoices(formDef);

        List<XFormChoiceExtractor.ChoiceList> choiceLists = result.getChoiceLists();
        XFormChoiceExtractor.ChoiceList soundList = choiceLists.stream()
                .filter(cl -> "/data/sound".equals(cl.getXpathReference()))
                .findFirst()
                .orElseThrow();
        
        assertEquals(2, soundList.getChoices().size());
        
        XFormChoiceExtractor.Choice dogChoice = soundList.getChoices().stream()
                .filter(c -> "dog".equals(c.getValue()))
                .findFirst()
                .orElseThrow();
        assertEquals("Dog Bark", dogChoice.getLabel());
        assertEquals("jr://audio/dog.mp3", dogChoice.getAudioRef());
        
        XFormChoiceExtractor.Choice catChoice = soundList.getChoices().stream()
                .filter(c -> "cat".equals(c.getValue()))
                .findFirst()
                .orElseThrow();
        assertEquals("jr://audio/cat.mp3", catChoice.getAudioRef());
    }

    @Test
    void extractChoices_withNestedChoices_returnsSeparateLists() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_NESTED_CHOICES);
        XFormChoiceExtractor.ChoiceExtractionResult result = choiceExtractor.extractChoices(formDef);

        List<XFormChoiceExtractor.ChoiceList> choiceLists = result.getChoiceLists();
        assertEquals(2, choiceLists.size());
        
        XFormChoiceExtractor.ChoiceList regionList = choiceLists.stream()
                .filter(cl -> "/data/region".equals(cl.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertEquals(2, regionList.getChoices().size());
        
        XFormChoiceExtractor.ChoiceList districtList = choiceLists.stream()
                .filter(cl -> "/data/district".equals(cl.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertEquals(2, districtList.getChoices().size());
    }

    @Test
    void extractChoices_nullFormDef_returnsEmptyResult() {
        XFormChoiceExtractor.ChoiceExtractionResult result = choiceExtractor.extractChoices((FormDef) null);
        assertNotNull(result);
        assertEquals(0, result.getChoiceListCount());
    }

    @Test
    void getChoicesForXPath_returnsCorrectChoices() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        List<XFormChoiceExtractor.Choice> choices = choiceExtractor.getChoicesForXPath(formDef, "/data/gender");

        assertNotNull(choices);
        assertEquals(3, choices.size());
        
        XFormChoiceExtractor.Choice maleChoice = choices.stream()
                .filter(c -> "male".equals(c.getValue()))
                .findFirst()
                .orElseThrow();
        assertEquals("Male", maleChoice.getLabel());
    }

    @Test
    void getChoicesForXPath_unknownReference_returnsEmptyList() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        List<XFormChoiceExtractor.Choice> choices = choiceExtractor.getChoicesForXPath(formDef, "/data/unknown");
        assertNotNull(choices);
        assertTrue(choices.isEmpty());
    }

    @Test
    void getChoiceListNames_returnsAllListNames() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        List<String> listNames = choiceExtractor.getChoiceListNames(formDef);

        assertNotNull(listNames);
        assertEquals(3, listNames.size());
        assertTrue(listNames.contains("/data/gender"));
        assertTrue(listNames.contains("/data/colors"));
        assertTrue(listNames.contains("/data/country"));
    }

    @Test
    void extractChoices_withSelectOneAndSelect_returnsCorrectTypes() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        XFormChoiceExtractor.ChoiceExtractionResult result = choiceExtractor.extractChoices(formDef);

        List<XFormChoiceExtractor.ChoiceList> choiceLists = result.getChoiceLists();
        
        XFormChoiceExtractor.ChoiceList genderList = choiceLists.stream()
                .filter(cl -> "/data/gender".equals(cl.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertEquals("select1", genderList.getSelectType());
        
        XFormChoiceExtractor.ChoiceList colorsList = choiceLists.stream()
                .filter(cl -> "/data/colors".equals(cl.getXpathReference()))
                .findFirst()
                .orElseThrow();
        assertEquals("select", colorsList.getSelectType());
    }
}