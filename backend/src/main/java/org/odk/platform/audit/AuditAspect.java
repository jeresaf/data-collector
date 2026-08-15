package org.odk.platform.audit;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.PreRemove;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.odk.platform.domain.Auditable;
import org.odk.platform.domain.UserPrincipal;
import org.odk.platform.security.CurrentUserService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final CurrentUserService currentUserService;

    @Pointcut("execution(* org.odk.platform.repository.*.save*(..))")
    public void saveOperations() {}

    @Pointcut("execution(* org.odk.platform.repository.*.delete*(..))")
    public void deleteOperations() {}

    @AfterReturning(pointcut = "saveOperations()", returning = "result")
    public void auditSave(JoinPoint joinPoint, Object result) {
        if (result instanceof Auditable auditable) {
            auditEntity(auditable, "CREATE");
        }
    }

    @AfterReturning(pointcut = "deleteOperations()", returning = "result")
    public void auditDelete(JoinPoint joinPoint, Object result) {
        Object[] args = joinPoint.getArgs();
        if (args.length > 0 && args[0] instanceof Auditable auditable) {
            auditEntity(auditable, "DELETE");
        }
    }

    private void auditEntity(Auditable entity, String action) {
        try {
            Optional<UserPrincipal> currentUser = currentUserService.getCurrentUser();
            UUID actorId = currentUser.map(UserPrincipal::getId).orElse(null);
            String actorType = currentUser.isPresent() ? "USER" : "SYSTEM";

            String entityName = entity.getClass().getSimpleName();
            UUID entityId = entity.getId();
            String entityDisplayName = getEntityDisplayName(entity);

            String actionName = entity.getCreatedAt() == null || action.equals("DELETE") ? action : "UPDATE";

            // The actual audit logging is done via @PrePersist/@PreUpdate/@PreRemove in the entity
            // This aspect captures the action for additional context if needed
            log.debug("Entity {} {} by user {}", entityName, actionName, actorId);
        } catch (Exception e) {
            log.warn("Failed to audit entity: {}", e.getMessage());
        }
    }

    private String getEntityDisplayName(Auditable entity) {
        try {
            if (entity instanceof org.odk.platform.domain.Project project) {
                return project.getName();
            } else if (entity instanceof org.odk.platform.domain.XForm form) {
                return form.getTitle();
            } else if (entity instanceof org.odk.platform.domain.User user) {
                return user.getUsername();
            } else if (entity instanceof org.odk.platform.domain.AppUser appUser) {
                return appUser.getUsername();
            } else if (entity instanceof org.odk.platform.domain.Submission submission) {
                return submission.getInstanceId();
            }
        } catch (Exception e) {
            // Ignore
        }
        return entity.getId() != null ? entity.getId().toString() : "new";
    }
}
