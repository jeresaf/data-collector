package org.odk.platform.service;

import org.javarosa.core.model.FormDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavaRosaFormValidatorTest {

    private JavaRosaFormValidator validator;
    private JavaRosaFormLoader formLoader;

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
                    </input>
                    <input ref="/data/age">
                        <label>Age</label>
                    </input>
                </h:body>
            </h:html>
            """;

    private static final String INVALID_XFORM_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml">
                <h:head>
                    <h:title>Invalid Form</h:title>
                </h:head>
                <h:body>
                    <p>Not a valid XForm</p>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_DUPLICATE_BINDS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Duplicate Binds Form</h:title>
                    <model>
                        <instance>
                            <data id="duplicate_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <name/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/name" type="string" required="true()"/>
                        <bind nodeset="/data/name" type="int" required="false()"/>
                    </model>
                </h:head>
                <h:body>
                    <input ref="/data/name">
                        <label>Name</label>
                    </input>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_INVALID_CONSTRAINT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Invalid Constraint Form</h:title>
                    <model>
                        <instance>
                            <data id="invalid_constraint_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <age/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/age" type="int" constraint=". > 100 and . < 0"/>
                    </model>
                </h:head>
                <h:body>
                    <input ref="/data/age">
                        <label>Age</label>
                    </input>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_REPEAT_GROUP = """
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

    private static final String XFORM_WITH_SELECT_ONE = """
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

    private static final String XFORM_WITH_ITEXT = """
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
                    </model>
                    <itext>
                        <translation lang="en" default="true">
                            <text id="/data/name:label">
                                <value>Name (English)</value>
                            </text>
                        </translation>
                        <translation lang="fr">
                            <text id="/data/name:label">
                                <value>Nom (Français)</value>
                            </text>
                        </translation>
                    </itext>
                </h:head>
                <h:body>
                    <input ref="/data/name">
                        <label ref="jr:itext('/data/name:label')"/>
                    </input>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_RELEVANT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
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
                    <input ref="/data/has_children">
                        <label>Has Children</label>
                    </input>
                    <input ref="/data/num_children">
                        <label>Number of Children</label>
                    </input>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_CALCULATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
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
                    <input ref="/data/price">
                        <label>Price</label>
                    </input>
                    <input ref="/data/quantity">
                        <label>Quantity</label>
                    </input>
                    <output ref="/data/total">
                        <label>Total</label>
                    </output>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_MEDIA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
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
                    <upload ref="/data/photo" mediatype="image/*">
                        <label>Photo</label>
                    </upload>
                    <upload ref="/data/audio" mediatype="audio/*">
                        <label>Audio</label>
                    </upload>
                    <upload ref="/data/video" mediatype="video/*">
                        <label>Video</label>
                    </upload>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_ENTITIES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms" xmlns:entity="http://opendatakit.org/xforms/entity">
                <h:head>
                    <h:title>Entity Form</h:title>
                    <model>
                        <instance>
                            <data id="entity_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms" xmlns:entity="http://opendatakit.org/xforms/entity">
                                <entity>
                                    <entity_id/>
                                    <label/>
                                </entity>
                                <name/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <bind nodeset="/data/entity/entity_id" type="string" required="true()"/>
                        <bind nodeset="/data/entity/label" type="string" required="true()"/>
                        <bind nodeset="/data/name" type="string"/>
                    </model>
                </h:head>
                <h:body>
                    <input ref="/data/entity/label">
                        <label>Entity Label</label>
                    </input>
                    <input ref="/data/name">
                        <label>Name</label>
                    </input>
                </h:body>
            </h:html>
            """;

    private static final String XFORM_WITH_EXTERNAL_DATASET = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms" xmlns:jr="http://openrosa.org/javarosa">
                <h:head>
                    <h:title>External Dataset Form</h:title>
                    <model>
                        <instance>
                            <data id="external_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                <facility_id/>
                                <facility_name/>
                                <meta>
                                    <instanceID/>
                                </meta>
                            </data>
                        </instance>
                        <instance id="facilities" src="jr://file-csv/facilities.csv"/>
                        <bind nodeset="/data/facility_id" type="string" required="true()"/>
                        <bind nodeset="/data/facility_name" type="string" calculate="instance('facilities')/root/item[name = /data/facility_id]/label"/>
                    </model>
                </h:head>
                <h:body>
                    <input ref="/data/facility_id">
                        <label>Facility ID</label>
                    </input>
                    <output ref="/data/facility_name">
                        <label>Facility Name</label>
                    </output>
                </h:body>
            </h:html>
            """;

    @BeforeEach
    void setUp() {
        formLoader = new JavaRosaFormLoader();
        validator = new JavaRosaFormValidator(formLoader);
    }

    @Test
    void validateForm_validXForm_returnsValidResult() {
        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm(VALID_XFORM_XML);

        // Then
        assertTrue(result.isValid());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    void validateForm_invalidXForm_returnsInvalidResult() {
        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm(INVALID_XFORM_XML);

        // Then
        assertFalse(result.isValid());
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    void validateForm_nullXml_returnsInvalidResult() {
        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm(null);

        // Then
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("null") || e.contains("empty")));
    }

    @Test
    void validateForm_emptyXml_returnsInvalidResult() {
        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm("");

        // Then
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("null") || e.contains("empty")));
    }

    @Test
    void validateForm_withDuplicateBinds_returnsInvalidResult() {
        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm(XFORM_WITH_DUPLICATE_BINDS);

        // Then
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.toLowerCase().contains("duplicate") || e.toLowerCase().contains("bind")));
    }

    @Test
    void validateForm_withInvalidConstraint_returnsInvalidResult() {
        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm(XFORM_WITH_INVALID_CONSTRAINT);

        // Then
        assertFalse(result.isValid());
    }

    @Test
    void validateForm_withRepeatGroup_returnsValidResult() {
        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm(XFORM_WITH_REPEAT_GROUP);

        // Then
        assertTrue(result.isValid());
    }

    @Test
    void validateForm_withSelectOne_returnsValidResult() {
        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm(XFORM_WITH_SELECT_ONE);

        // Then
        assertTrue(result.isValid());
    }

    @Test
    void validateForm_withIText_returnsValidResult() {
        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm(XFORM_WITH_ITEXT);

        // Then
        assertTrue(result.isValid());
    }

    @Test
    void validateForm_withRelevantExpressions_returnsValidResult() {
        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm(XFORM_WITH_RELEVANT);

        // Then
        assertTrue(result.isValid());
    }

    @Test
    void validateForm_withCalculate_returnsValidResult() {
        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm(XFORM_WITH_CALCULATE);

        // Then
        assertTrue(result.isValid());
    }

    @Test
    void validateForm_withMediaFiles_returnsValidResult() {
        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm(XFORM_WITH_MEDIA);

        // Then
        assertTrue(result.isValid());
    }

    @Test
    void validateForm_withEntities_returnsValidResult() {
        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm(XFORM_WITH_ENTITIES);

        // Then
        assertTrue(result.isValid());
    }

    @Test
    void validateForm_withExternalDataset_returnsValidResult() {
        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm(XFORM_WITH_EXTERNAL_DATASET);

        // Then
        assertTrue(result.isValid());
    }

    @Test
    void validateFormDef_validFormDef_returnsValidResult() {
        // Given
        FormDef formDef = formLoader.loadFormFromXml(VALID_XFORM_XML);

        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateFormDef(formDef);

        // Then
        assertTrue(result.isValid());
    }

    @Test
    void validateFormDef_nullFormDef_returnsInvalidResult() {
        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateFormDef(null);

        // Then
        assertFalse(result.isValid());
    }

    @Test
    void canCreateInstance_validForm_returnsTrue() {
        // When
        boolean canCreate = validator.canCreateInstance(VALID_XFORM_XML);

        // Then
        assertTrue(canCreate);
    }

    @Test
    void canCreateInstance_invalidForm_returnsFalse() {
        // When
        boolean canCreate = validator.canCreateInstance(INVALID_XFORM_XML);

        // Then
        assertFalse(canCreate);
    }

    @Test
    void validateForm_withMissingFormId_returnsWarning() {
        // Given
        String xformMissingId = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>Missing ID Form</h:title>
                        <model>
                            <instance>
                                <data version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
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

        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm(xformMissingId);

        // Then
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("MISSING_FORM_ID")));
    }

    @Test
    void validateForm_withMissingTitle_returnsWarning() {
        // Given
        String xformMissingTitle = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <model>
                            <instance>
                                <data id="missing_title_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
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

        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm(xformMissingTitle);

        // Then
        assertTrue(result.isValid()); // Missing title is a warning, not an error
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("MISSING_FORM_TITLE")));
    }

    @Test
    void validateForm_withMissingBody_returnsError() {
        // Given
        String xformMissingBody = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>Missing Body Form</h:title>
                        <model>
                            <instance>
                                <data id="missing_body_form" version="1.0" xmlns:jr="http://openrosa.org/javarosa" xmlns:orx="http://openrosa.org/xforms">
                                    <name/>
                                    <meta>
                                        <instanceID/>
                                    </meta>
                                </data>
                            </instance>
                            <bind nodeset="/data/name" type="string" required="true()"/>
                        </model>
                    </h:head>
                </h:html>
                """;

        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm(xformMissingBody);

        // Then
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("MISSING_FORM_BODY")));
    }

    @Test
    void validateForm_withMissingInstance_returnsError() {
        // Given
        String xformMissingInstance = """
                <?xml version="1.0" encoding="UTF-8"?>
                <h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms" xmlns="http://www.w3.org/2002/xforms">
                    <h:head>
                        <h:title>Missing Instance Form</h:title>
                        <model>
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

        // When
        JavaRosaFormValidator.ValidationResult result = validator.validateForm(xformMissingInstance);

        // Then
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("MISSING_INSTANCE")));
    }
}