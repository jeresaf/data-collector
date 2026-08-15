package org.odk.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.domain.*;
import org.odk.platform.repository.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FormAssignmentServiceTest {
    @Mock AppUserProjectRepository assignments;
    @Mock AppUserRepository appUsers;
    @Mock ProjectRepository projects;
    @Mock XFormRepository forms;
    @InjectMocks FormAssignmentService service;

    @Test
    void assignsNewFormAndAccessCheckUsesActiveAssignments() {
        UUID projectId = UUID.randomUUID(), appUserId = UUID.randomUUID(), formId = UUID.randomUUID();
        Project project = Project.builder().id(projectId).build();
        AppUser appUser = AppUser.builder().id(appUserId).build();
        XForm form = XForm.builder().id(formId).project(project).build();
        User actor = User.builder().id(UUID.randomUUID()).username("pm").build();
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(appUsers.findById(appUserId)).thenReturn(Optional.of(appUser));
        when(forms.findAllById(List.of(formId))).thenReturn(List.of(form));
        when(assignments.findByAppUserIdAndProjectId(appUserId, projectId)).thenReturn(List.of());
        when(assignments.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        var saved = service.assignFormsToAppUser(projectId, appUserId, List.of(formId), actor);
        assertEquals(1, saved.size());
        assertEquals("ACTIVE", saved.get(0).getStatus());
        assertSame(form, saved.get(0).getForm());
        when(assignments.findByAppUserIdAndProjectId(appUserId, projectId)).thenReturn(saved);
        assertTrue(service.hasFormAccess(appUserId, projectId, formId));
    }

    @Test
    void reconcilingExistingAssignmentUsesMutableWorkingSet() {
        UUID projectId = UUID.randomUUID(), appUserId = UUID.randomUUID(), formId = UUID.randomUUID();
        Project project = Project.builder().id(projectId).build();
        AppUser appUser = AppUser.builder().id(appUserId).build();
        XForm form = XForm.builder().id(formId).project(project).build();
        AppUserProject existing = AppUserProject.builder()
                .appUser(appUser).project(project).form(form).status("INACTIVE").build();
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(appUsers.findById(appUserId)).thenReturn(Optional.of(appUser));
        when(forms.findAllById(List.of(formId))).thenReturn(List.of(form));
        when(assignments.findByAppUserIdAndProjectId(appUserId, projectId)).thenReturn(List.of(existing));
        when(assignments.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> service.assignFormsToAppUser(projectId, appUserId, List.of(formId), null));
        assertEquals("ACTIVE", existing.getStatus());
        verify(assignments).save(existing);
    }

    @Test
    void rejectsFormFromDifferentProject() {
        UUID projectId = UUID.randomUUID(), appUserId = UUID.randomUUID(), formId = UUID.randomUUID();
        Project project = Project.builder().id(projectId).build();
        XForm foreign = XForm.builder().id(formId).project(Project.builder().id(UUID.randomUUID()).build()).build();
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(appUsers.findById(appUserId)).thenReturn(Optional.of(AppUser.builder().id(appUserId).build()));
        when(forms.findAllById(List.of(formId))).thenReturn(List.of(foreign));
        assertThrows(IllegalArgumentException.class,
                () -> service.assignFormsToAppUser(projectId, appUserId, List.of(formId), null));
    }
}
