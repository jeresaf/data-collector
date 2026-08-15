package org.odk.platform.service;

import org.javarosa.form.api.FormEntryController;
import org.junit.jupiter.api.Test;
import org.odk.platform.domain.EntityItem;
import org.odk.platform.domain.EntityItemProperty;
import org.odk.platform.domain.EntityList;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.EntityListRepository;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntityListDatasetServiceTest {

    private static final String ENTITY_LOOKUP_XFORM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Entity Lookup</h:title>
                    <model>
                        <instance>
                            <data id="entity_lookup">
                                <tree/>
                                <species/>
                                <meta><instanceID/></meta>
                            </data>
                        </instance>
                        <instance id="trees" src="jr://file-csv/trees.csv"/>
                        <bind nodeset="/data/tree" type="select1"/>
                        <bind nodeset="/data/species" type="string"
                              calculate="instance('trees')/root/item[name = /data/tree]/species"/>
                    </model>
                </h:head>
                <h:body>
                    <select1 ref="/data/tree">
                        <label>Tree</label>
                        <itemset nodeset="instance('trees')/root/item[status = 'active']">
                            <label ref="label"/>
                            <value ref="name"/>
                        </itemset>
                    </select1>
                </h:body>
            </h:html>
            """;

    @Test
    void projectEntityList_isMaterializedAsStandardOdkDatasetAndConsumedByJavaRosa() {
        EntityListRepository repository = mock(EntityListRepository.class);
        EntityListDatasetService service = new EntityListDatasetService(repository);

        UUID projectId = UUID.randomUUID();
        Project project = new Project();
        project.setId(projectId);
        XForm xForm = new XForm();
        xForm.setProject(project);
        FormVersion formVersion = new FormVersion();
        formVersion.setId(UUID.randomUUID());
        formVersion.setForm(xForm);
        formVersion.setXmlContent(ENTITY_LOOKUP_XFORM);

        EntityList trees = entityList("trees",
                item("uuid:tree-1", "Mango near school", Map.of("species", "mango", "status", "active")),
                item("uuid:tree-2", "Old pine", Map.of("species", "pine", "status", "retired")));
        when(repository.findByProjectIdAndNameAndIsActiveTrue(projectId, "trees"))
                .thenReturn(Optional.of(trees));

        EntityListDatasetService.DatasetBundle bundle = service.loadReferencedEntityDatasets(formVersion);
        assertTrue(bundle.warnings().isEmpty(), bundle.warnings().toString());
        assertTrue(bundle.datasets().containsKey("trees"));
        assertTrue(bundle.datasets().containsKey("trees.csv"));

        String csv = new String(bundle.datasets().get("trees.csv"), StandardCharsets.UTF_8);
        assertEquals("name,label,species,status\n"
                        + "uuid:tree-1,Mango near school,mango,active\n"
                        + "uuid:tree-2,Old pine,pine,retired\n",
                csv);

        FormDefinition form = FormDefinition.createNew(
                ENTITY_LOOKUP_XFORM,
                formVersion.getId().toString(),
                bundle.datasets(),
                bundle.warnings());

        assertEquals(FormEntryController.EVENT_QUESTION, form.stepToNextEvent());
        List<FormDefinition.SelectChoice> choices = form.getCurrentChoices();
        assertEquals(List.of("uuid:tree-1"), choices.stream().map(FormDefinition.SelectChoice::getValue).toList());
        assertEquals(List.of("Mango near school"), choices.stream().map(FormDefinition.SelectChoice::getLabel).toList());

        assertEquals(FormEntryController.ANSWER_OK, form.answerQuestion(form.parseAnswer("uuid:tree-1")));
        assertEquals("mango", form.evaluateXPath("/data/species"));
    }

    @Test
    void invalidOrReservedEntityProperties_areWarnedAndExcluded() {
        EntityListDatasetService service = new EntityListDatasetService(mock(EntityListRepository.class));
        EntityList list = entityList("people",
                item("p1", "Person One", Map.of(
                        "phone number", "123",
                        "label", "shadow label",
                        "phone", "456")));

        EntityListDatasetService.CsvResult result = service.toCsv(list);

        assertEquals("name,label,phone\np1,Person One,456\n", result.csv());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("phone number")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("reserved ODK dataset column 'label'")));
    }

    private static EntityList entityList(String name, EntityItem... items) {
        EntityList list = EntityList.builder()
                .name(name)
                .displayName(name)
                .isActive(true)
                .items(new LinkedHashSet<>())
                .build();
        for (EntityItem item : items) {
            item.setEntityList(list);
            list.getItems().add(item);
        }
        return list;
    }

    private static EntityItem item(String entityId, String label, Map<String, String> properties) {
        EntityItem item = EntityItem.builder()
                .entityId(entityId)
                .label(label)
                .isActive(true)
                .properties(new LinkedHashSet<>())
                .build();
        properties.forEach((name, value) -> {
            EntityItemProperty property = EntityItemProperty.builder()
                    .entityItem(item)
                    .propertyName(name)
                    .propertyValue(value)
                    .build();
            item.getProperties().add(property);
        });
        return item;
    }
}
