package org.odk.platform.service;

import org.javarosa.form.api.FormEntryController;
import org.junit.jupiter.api.Test;
import org.odk.platform.domain.EntityItem;
import org.odk.platform.domain.EntityItemProperty;
import org.odk.platform.domain.EntityList;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.User;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.EntityListRepository;
import org.odk.platform.repository.FormVersionRepository;
import org.odk.platform.repository.ProjectRepository;
import org.odk.platform.repository.UserRepository;
import org.odk.platform.repository.XFormRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WebFormEntityListIntegrationTest {

    private static final String XFORM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <h:html xmlns:h="http://www.w3.org/1999/xhtml"
                    xmlns="http://www.w3.org/2002/xforms">
                <h:head>
                    <h:title>Shared Entities</h:title>
                    <model>
                        <instance><data id="entities"><household/><phone/><meta><instanceID/></meta></data></instance>
                        <instance id="households" src="jr://file-csv/households.csv"/>
                        <bind nodeset="/data/household" type="select1"/>
                        <bind nodeset="/data/phone" type="string"
                              calculate="instance('households')/root/item[name = /data/household]/phone"/>
                    </model>
                </h:head>
                <h:body>
                    <select1 ref="/data/household">
                        <label>Household</label>
                        <itemset nodeset="instance('households')/root/item[status = 'active']">
                            <label ref="label"/><value ref="name"/>
                        </itemset>
                    </select1>
                </h:body>
            </h:html>
            """;

    @Autowired WebFormRuntimeService webFormRuntimeService;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired XFormRepository xFormRepository;
    @Autowired FormVersionRepository formVersionRepository;
    @Autowired EntityListRepository entityListRepository;

    @Test
    void runtimeResolvesProjectEntityListWithoutFormVersionFileUpload() {
        User user = new User();
        user.setUsername("entity-runtime-owner");
        user.setEmail("entity-runtime-owner@example.com");
        user.setPasswordHash("hashed");
        user.setIsActive(true);
        user = userRepository.save(user);

        Project project = Project.builder()
                .name("Entity Runtime Project")
                .owner(user)
                .createdBy(user)
                .build();
        project = projectRepository.save(project);

        XForm xForm = XForm.builder()
                .project(project)
                .title("Shared Entities")
                .xmlns("http://example.org/shared-entities")
                .xmlContent(XFORM)
                .createdBy(user)
                .build();
        xForm = xFormRepository.save(xForm);

        FormVersion version = FormVersion.builder()
                .form(xForm)
                .version("1")
                .xmlContent(XFORM)
                .createdBy(user)
                .build();
        version = formVersionRepository.save(version);

        EntityList list = EntityList.builder()
                .project(project)
                .name("households")
                .displayName("Households")
                .items(new LinkedHashSet<>())
                .build();
        EntityItem item = EntityItem.builder()
                .entityList(list)
                .entityId("uuid:hh-001")
                .label("Household 001")
                .properties(new LinkedHashSet<>())
                .build();
        item.getProperties().add(EntityItemProperty.builder()
                .entityItem(item).propertyName("status").propertyValue("active").build());
        item.getProperties().add(EntityItemProperty.builder()
                .entityItem(item).propertyName("phone").propertyValue("+256700000001").build());
        list.getItems().add(item);
        entityListRepository.saveAndFlush(list);

        FormDefinition form = webFormRuntimeService.getFormDefinitionForVersion(version.getId());
        assertTrue(form.getCompatibilityWarnings().isEmpty(), form.getCompatibilityWarnings().toString());
        assertEquals(FormEntryController.EVENT_QUESTION, form.stepToNextEvent());
        assertEquals(List.of("uuid:hh-001"),
                form.getCurrentChoices().stream().map(FormDefinition.SelectChoice::getValue).toList());

        assertEquals(FormEntryController.ANSWER_OK, form.answerQuestion(form.parseAnswer("uuid:hh-001")));
        assertEquals("+256700000001", form.evaluateXPath("/data/phone"));
    }
}
