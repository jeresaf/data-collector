package org.odk.platform.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.service.IdentityAdminService;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityAdminControllerTest {

    @Mock IdentityAdminService service;
    @InjectMocks IdentityAdminController controller;

    @Test
    void createAndRoleEndpointsDelegateToService() {
        UUID userId = UUID.randomUUID();

        var created = new IdentityAdminService.WebUserView(
                userId, "user1", "user1@example.org", "User", "One", "User One",
                null, true, true, List.of("VIEWER"),
                null, null, null);

        var createCommand = new IdentityAdminService.CreateWebUserCommand(
                "user1", "user1@example.org", "secret123",
                "User", "One", null, Set.of("VIEWER"));

        var roleCommand = new IdentityAdminService.ReplaceRolesCommand(
                Set.of("DATA_ANALYST"));

        var changed = new IdentityAdminService.WebUserView(
                userId, "user1", "user1@example.org", "User", "One", "User One",
                null, true, true, List.of("DATA_ANALYST"),
                null, null, null);

        when(service.createUser(createCommand)).thenReturn(created);
        when(service.replaceGlobalRoles(userId, roleCommand)).thenReturn(changed);

        var response = controller.createUser(createCommand);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(created);
        assertThat(controller.replaceRoles(userId, roleCommand).roles())
                .containsExactly("DATA_ANALYST");
    }
}
