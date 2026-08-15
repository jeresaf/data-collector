package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormDefinitionCompatibilityPreflightTest {

    private static final String MISSING_DATASET_XFORM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Missing Dataset</h:title>
                    <model>
                        <instance><data id="missing"><choice/><meta><instanceID/></meta></data></instance>
                        <instance id="people" src="jr://file-csv/people.csv"/>
                        <bind nodeset="/data/choice" type="select1"/>
                    </model>
                </h:head>
                <h:body>
                    <select1 ref="/data/choice">
                        <label>Person</label>
                        <itemset nodeset="instance('people')/root/item">
                            <label ref="label"/><value ref="name"/>
                        </itemset>
                    </select1>
                </h:body>
            </h:html>
            """;

    @Test
    void missingDataset_keepsFormOpenAndReturnsExplicitCompatibilityWarning() {
        FormDefinition form = assertDoesNotThrow(() ->
                FormDefinition.createNew(MISSING_DATASET_XFORM, "missing-dataset", Map.of()));

        assertTrue(form.getCompatibilityWarnings().stream()
                .anyMatch(warning -> warning.contains("people.csv") && warning.contains("Entity List")));
    }

    @Test
    void providerWarnings_areMergedWithPreprocessorWarningsWithoutDuplicates() {
        FormDefinition form = FormDefinition.createNew(
                MISSING_DATASET_XFORM,
                "warning-merge",
                Map.of(),
                java.util.List.of("Entity List 'people' property 'bad field' is invalid.",
                        "Entity List 'people' property 'bad field' is invalid."));

        assertTrue(form.getCompatibilityWarnings().contains(
                "Entity List 'people' property 'bad field' is invalid."));
        long count = form.getCompatibilityWarnings().stream()
                .filter("Entity List 'people' property 'bad field' is invalid."::equals)
                .count();
        assertTrue(count == 1);
    }
}
