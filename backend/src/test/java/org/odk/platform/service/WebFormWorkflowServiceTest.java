package org.odk.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.odk.platform.domain.User;
import org.odk.platform.security.CurrentUserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebFormWorkflowServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private WebFormAccessService webFormAccessService;

    @InjectMocks
    private WebFormWorkflowService service;

    @Test
    void catalogRequiresAuthenticatedWebUser() {
        when(currentUserService.getCurrentWebUser()).thenReturn(Optional.empty());

        assertThatThrownBy(service::listAvailableForms)
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("authenticated web user");

        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(webFormAccessService);
    }

    @Test
    void sessionListRejectsUnsupportedStatusBeforeQueryingDatabase() {
        User user = mock(User.class);
        when(currentUserService.getCurrentWebUser()).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.listSessions("deleted"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported web form session status");

        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(webFormAccessService);
    }
}
