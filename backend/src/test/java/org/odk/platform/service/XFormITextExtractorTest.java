package org.odk.platform.service;

import org.javarosa.core.model.FormDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class XFormITextExtractorTest {

    private XFormITextExtractor itextExtractor;
    private JavaRosaFormLoader formLoader;

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
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/name" type="string" required="true()"/>
                        <bind nodeset="/data/age" type="int" required="true()"/>
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
                            <text id="submit_button">
                                <value>Submit</value>
                            </text>
                        </translation>
                        <translation lang="fr">
                            <text id="/data/name:label">
                                <value>Nom</value>
                            </text>
                            <text id="/data/name:hint">
                                <value>Entrez votre nom complet</value>
                            </text>
                            <text id="/data/age:label">
                                <value>Âge</value>
                            </text>
                            <text id="/data/age:hint">
                                <value>Entrez votre âge en années</value>
                            </text>
                            <text id="submit_button">
                                <value>Soumettre</value>
                            </text>
                        </translation>
                        <translation lang="es">
                            <text id="/data/name:label">
                                <value>Nombre</value>
                            </text>
                            <text id="/data/name:hint">
                                <value>Ingrese su nombre completo</value>
                            </text>
                            <text id="/data/age:label">
                                <value>Edad</value>
                            </text>
                            <text id="/data/age:hint">
                                <value>Ingrese su edad en años</value>
                            </text>
                            <text id="submit_button">
                                <value>Enviar</value>
                            </text>
                        </translation>
                    </itext>
                </h:head>
                <h:body>
                    <input ref="/data/name">
                        <label ref="jr:itext('/data/name:label')"/>
                        <hint ref="jr:itext('/data/name:hint')"/>
                    </input>
                    <input ref="/data/age">
                        <label ref="jr:itext('/data/age:label')"/>
                        <hint ref="jr:itext('/data/age:hint')"/>
                    </input>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_IMAGE_ITEXT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms" xmlns:jr="http://openrosa.org/javarosa">
                <h:head>
                    <h:title>Image IText Form</h:title>
                    <model>
                        <instance>
                            <data id="image_itext_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <photo/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/photo" type="binary" mediatype="image/*"/>
                    </model>
                    <itext>
                        <translation lang="en" default="true">
                            <text id="/data/photo:label">
                                <value>Photo</value>
                            </text>
                            <text id="/data/photo:hint">
                                <value>Take a photo</value>
                            </text>
                            <text id="/data/photo:guidance">
                                <value>Make sure the photo is clear</value>
                            </text>
                        </translation>
                    </itext>
                </h:head>
                <h:body>
                    <upload ref="/data/photo" mediatype="image/*">
                        <label ref="jr:itext('/data/photo:label')"/>
                        <hint ref="jr:itext('/data/photo:hint')"/>
                        <guidance ref="jr:itext('/data/photo:guidance')"/>
                    </upload>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_CHOICE_ITEXT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms" xmlns:jr="http://openrosa.org/javarosa">
                <h:head>
                    <h:title>Choice IText Form</h:title>
                    <model>
                        <instance>
                            <data id="choice_itext_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <gender/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/gender" type="select1" required="true()"/>
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
                            <text id="/data/gender:option2">
                                <value>Other</value>
                            </text>
                        </translation>
                        <translation lang="fr">
                            <text id="/data/gender:label">
                                <value>Genre</value>
                            </text>
                            <text id="/data/gender:option0">
                                <value>Homme</value>
                            </text>
                            <text id="/data/gender:option1">
                                <value>Femme</value>
                            </text>
                            <text id="/data/gender:option2">
                                <value>Autre</value>
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
                        <item>
                            <label ref="jr:itext('/data/gender:option2')"/>
                            <value>other</value>
                        </item>
                    </select1>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITHOUT_ITEXT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>No IText Form</h:title>
                    <model>
                        <instance>
                            <data id="no_itext_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <name/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/name" type="string" required="true()"/>
                    </model>
                </h:head>
                <h:body>
                    <input ref="/data/name">
                        <label>Name</label>
                    </input>
                </h:body>
            </h:html>
            """;

    @BeforeEach
    void setUp() {
        formLoader = new JavaRosaFormLoader();
        itextExtractor = new XFormITextExtractor(formLoader);
    }

    @Test
    void extractIText_validForm_returnsAllTranslations() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        XFormITextExtractor.ITextExtractionResult result = itextExtractor.extractIText(formDef);

        assertNotNull(result);
        assertEquals(3, result.getLanguageCount());
        List<String> languages = result.getLanguages();
        assertTrue(languages.contains("en"));
        assertTrue(languages.contains("fr"));
        assertTrue(languages.contains("es"));
        assertEquals("en", result.getDefaultLanguage());
    }

    @Test
    void extractIText_validForm_returnsCorrectTranslations() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        XFormITextExtractor.ITextExtractionResult result = itextExtractor.extractIText(formDef);

        Map<String, String> enTranslations = result.getTranslations("en");
        assertNotNull(enTranslations);
        assertEquals("Name", enTranslations.get("/data/name:label"));
        assertEquals("Enter your full name", enTranslations.get("/data/name:hint"));
        assertEquals("Age", enTranslations.get("/data/age:label"));
        assertEquals("Enter your age in years", enTranslations.get("/data/age:hint"));
        assertEquals("Submit", enTranslations.get("submit_button"));

        Map<String, String> frTranslations = result.getTranslations("fr");
        assertNotNull(frTranslations);
        assertEquals("Nom", frTranslations.get("/data/name:label"));
        assertEquals("Entrez votre nom complet", frTranslations.get("/data/name:hint"));
        assertEquals("Âge", frTranslations.get("/data/age:label"));
        assertEquals("Entrez votre âge en années", frTranslations.get("/data/age:hint"));
        assertEquals("Soumettre", frTranslations.get("submit_button"));

        Map<String, String> esTranslations = result.getTranslations("es");
        assertNotNull(esTranslations);
        assertEquals("Nombre", esTranslations.get("/data/name:label"));
        assertEquals("Ingrese su nombre completo", esTranslations.get("/data/name:hint"));
        assertEquals("Edad", esTranslations.get("/data/age:label"));
        assertEquals("Ingrese su edad en años", esTranslations.get("/data/age:hint"));
        assertEquals("Enviar", esTranslations.get("submit_button"));
    }

    @Test
    void extractIText_withImageIText_returnsGuidance() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_IMAGE_ITEXT);
        XFormITextExtractor.ITextExtractionResult result = itextExtractor.extractIText(formDef);

        Map<String, String> enTranslations = result.getTranslations("en");
        assertNotNull(enTranslations);
        assertEquals("Photo", enTranslations.get("/data/photo:label"));
        assertEquals("Take a photo", enTranslations.get("/data/photo:hint"));
        assertEquals("Make sure the photo is clear", enTranslations.get("/data/photo:guidance"));
    }

    @Test
    void extractIText_withChoiceIText_returnsChoiceTranslations() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITH_CHOICE_ITEXT);
        XFormITextExtractor.ITextExtractionResult result = itextExtractor.extractIText(formDef);

        Map<String, String> enTranslations = result.getTranslations("en");
        assertNotNull(enTranslations);
        assertEquals("Gender", enTranslations.get("/data/gender:label"));
        assertEquals("Male", enTranslations.get("/data/gender:option0"));
        assertEquals("Female", enTranslations.get("/data/gender:option1"));
        assertEquals("Other", enTranslations.get("/data/gender:option2"));

        Map<String, String> frTranslations = result.getTranslations("fr");
        assertNotNull(frTranslations);
        assertEquals("Genre", frTranslations.get("/data/gender:label"));
        assertEquals("Homme", frTranslations.get("/data/gender:option0"));
        assertEquals("Femme", frTranslations.get("/data/gender:option1"));
        assertEquals("Autre", frTranslations.get("/data/gender:option2"));
    }

    @Test
    void extractIText_withoutIText_returnsEmptyResult() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITHOUT_ITEXT);
        XFormITextExtractor.ITextExtractionResult result = itextExtractor.extractIText(formDef);

        assertNotNull(result);
        assertEquals(0, result.getLanguageCount());
        Object languages = result.getLanguages();
        if (languages instanceof List) {
            assertTrue(((List<?>) languages).isEmpty());
        } else if (languages instanceof String[]) {
            assertEquals(0, ((String[]) languages).length);
        }
        assertNull(result.getDefaultLanguage());
    }

    @Test
    void extractIText_nullFormDef_returnsEmptyResult() {
        XFormITextExtractor.ITextExtractionResult result = itextExtractor.extractIText((FormDef) null);
        assertNotNull(result);
        assertEquals(0, result.getLanguageCount());
    }

    @Test
    void getTranslation_returnsCorrectValue() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        
        String nameLabelEn = itextExtractor.getTranslation(formDef, "en", "/data/name:label");
        assertEquals("Name", nameLabelEn);
        
        String nameLabelFr = itextExtractor.getTranslation(formDef, "fr", "/data/name:label");
        assertEquals("Nom", nameLabelFr);
        
        String submitButtonEs = itextExtractor.getTranslation(formDef, "es", "submit_button");
        assertEquals("Enviar", submitButtonEs);
    }

    @Test
    void getTranslation_unknownKey_returnsNull() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        String translation = itextExtractor.getTranslation(formDef, "en", "/data/unknown:label");
        assertNull(translation);
    }

    @Test
    void getTranslation_unknownLanguage_returnsNull() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        String translation = itextExtractor.getTranslation(formDef, "de", "/data/name:label");
        assertNull(translation);
    }

    @Test
    void getAllTranslationsForKey_returnsAllLanguages() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        Map<String, String> translations = itextExtractor.getAllTranslationsForKey(formDef, "/data/name:label");

        assertNotNull(translations);
        assertEquals(3, translations.size());
        assertEquals("Name", translations.get("en"));
        assertEquals("Nom", translations.get("fr"));
        assertEquals("Nombre", translations.get("es"));
    }

    @Test
    void getDefaultLanguage_returnsCorrectLanguage() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        assertEquals("en", itextExtractor.getDefaultLanguage(formDef));
    }

    @Test
    void getDefaultLanguage_noIText_returnsNull() {
        FormDef formDef = formLoader.loadFormFromXml(XFORM_WITHOUT_ITEXT);
        assertNull(itextExtractor.getDefaultLanguage(formDef));
    }

    @Test
    void hasTranslation_returnsTrueForExistingKey() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        assertTrue(itextExtractor.hasTranslation(formDef, "en", "/data/name:label"));
        assertTrue(itextExtractor.hasTranslation(formDef, "fr", "/data/name:label"));
    }

    @Test
    void hasTranslation_returnsFalseForMissingKey() {
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);
        assertFalse(itextExtractor.hasTranslation(formDef, "en", "/data/unknown:label"));
        assertFalse(itextExtractor.hasTranslation(formDef, "de", "/data/name:label"));
    }
}