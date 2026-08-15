package org.odk.platform.repository;

import org.junit.jupiter.api.Test;
import org.odk.platform.domain.EntityItem;
import org.odk.platform.domain.EntityItemProperty;
import org.odk.platform.domain.EntityList;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EntityListRepositoryIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EntityListRepository entityListRepository;

    @Test
    void entityListItemsAndProperties_persistAndLoadAsGenericProjectData() {
        User user = new User();
        user.setUsername("entity-owner");
        user.setEmail("entity-owner@example.com");
        user.setPasswordHash("hashed");
        user.setIsActive(true);
        user = userRepository.save(user);

        Project project = Project.builder()
                .name("Entity Project")
                .owner(user)
                .createdBy(user)
                .build();
        project = projectRepository.save(project);

        EntityList list = EntityList.builder()
                .project(project)
                .name("households")
                .displayName("Households")
                .items(new LinkedHashSet<>())
                .build();
        EntityItem item = EntityItem.builder()
                .entityList(list)
                .entityId("uuid:household-1")
                .label("Household One")
                .properties(new LinkedHashSet<>())
                .build();
        item.getProperties().add(EntityItemProperty.builder()
                .entityItem(item)
                .propertyName("status")
                .propertyValue("active")
                .build());
        list.getItems().add(item);
        entityListRepository.saveAndFlush(list);

        EntityList loaded = entityListRepository
                .findByProjectIdAndNameAndIsActiveTrue(project.getId(), "households")
                .orElseThrow();

        assertEquals(1, loaded.getItems().size());
        EntityItem loadedItem = loaded.getItems().iterator().next();
        assertEquals("uuid:household-1", loadedItem.getEntityId());
        assertEquals("Household One", loadedItem.getLabel());
        assertTrue(loadedItem.getProperties().stream()
                .anyMatch(p -> p.getPropertyName().equals("status") && p.getPropertyValue().equals("active")));
    }
}
