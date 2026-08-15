package org.odk.platform.repository;

import org.junit.jupiter.api.Test;
import org.odk.platform.domain.AppUser;
import org.odk.platform.domain.AppUserProject;
import org.odk.platform.domain.Project;
import org.odk.platform.domain.User;
import org.odk.platform.domain.XForm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AppUserProjectRepositoryIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired XFormRepository xFormRepository;
    @Autowired AppUserProjectRepository appUserProjectRepository;

    @Test
    void sameAppUserAndProject_canPersistDifferentFormAssignments() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User owner = User.builder()
                .username("assignment-owner-" + suffix)
                .email("assignment-owner-" + suffix + "@example.com")
                .passwordHash("hashed")
                .isActive(true)
                .isVerified(true)
                .build();
        owner = userRepository.saveAndFlush(owner);

        Project project = Project.builder()
                .name("Multi-form Assignment " + suffix)
                .owner(owner)
                .createdBy(owner)
                .build();
        project = projectRepository.saveAndFlush(project);

        AppUser appUser = AppUser.builder()
                .username("collector-" + suffix)
                .email("collector-" + suffix + "@example.com")
                .passwordHash("hashed")
                .isActive(true)
                .isVerified(true)
                .build();
        appUser = appUserRepository.saveAndFlush(appUser);

        XForm firstForm = XForm.builder()
                .title("First Form")
                .xmlns("multi-form-first-" + suffix)
                .version("1")
                .xmlContent("<data id=\"first\"/>")
                .project(project)
                .createdBy(owner)
                .build();
        XForm secondForm = XForm.builder()
                .title("Second Form")
                .xmlns("multi-form-second-" + suffix)
                .version("1")
                .xmlContent("<data id=\"second\"/>")
                .project(project)
                .createdBy(owner)
                .build();
        List<XForm> forms = xFormRepository.saveAllAndFlush(List.of(firstForm, secondForm));

        AppUserProject firstAssignment = AppUserProject.builder()
                .appUser(appUser)
                .project(project)
                .form(forms.get(0))
                .assignedBy(owner)
                .status("ACTIVE")
                .build();
        AppUserProject secondAssignment = AppUserProject.builder()
                .appUser(appUser)
                .project(project)
                .form(forms.get(1))
                .assignedBy(owner)
                .status("ACTIVE")
                .build();

        appUserProjectRepository.saveAllAndFlush(List.of(firstAssignment, secondAssignment));

        List<AppUserProject> persisted = appUserProjectRepository
                .findByAppUserIdAndProjectId(appUser.getId(), project.getId());

        assertEquals(2, persisted.size());
        assertTrue(persisted.stream().allMatch(a -> "ACTIVE".equals(a.getStatus())));
        assertEquals(2, persisted.stream().map(a -> a.getForm().getId()).distinct().count());
    }
}
