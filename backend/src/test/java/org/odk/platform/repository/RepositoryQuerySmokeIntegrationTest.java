package org.odk.platform.repository;

import org.junit.jupiter.api.Test;
import org.odk.platform.audit.AuditLogDetailRepository;
import org.odk.platform.domain.FormStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * EPIC 21 data-layer certification smoke test.
 *
 * The purpose is not to duplicate service tests. It executes a representative
 * derived/custom query for every repository against the H2 mirror so schema,
 * entity mapping and Spring Data query regressions fail in one focused suite.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RepositoryQuerySmokeIntegrationTest {

    @Autowired AppUserProjectRepository appUserProjectRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired AuditLogDetailRepository auditLogDetailRepository;
    @Autowired EntityItemPropertyRepository entityItemPropertyRepository;
    @Autowired EntityItemRepository entityItemRepository;
    @Autowired EntityListRepository entityListRepository;
    @Autowired FormAttachmentRepository formAttachmentRepository;
    @Autowired FormExternalDatasetRepository formExternalDatasetRepository;
    @Autowired FormPublishHistoryRepository formPublishHistoryRepository;
    @Autowired FormVersionRepository formVersionRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectSettingsRepository projectSettingsRepository;
    @Autowired ReferenceItemPropertyRepository referenceItemPropertyRepository;
    @Autowired ReferenceItemRelationshipRepository referenceItemRelationshipRepository;
    @Autowired ReferenceItemRepository referenceItemRepository;
    @Autowired ReferenceListRepository referenceListRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired SubmissionAnswerRepository submissionAnswerRepository;
    @Autowired SubmissionAttachmentRepository submissionAttachmentRepository;
    @Autowired SubmissionEventRepository submissionEventRepository;
    @Autowired SubmissionRepository submissionRepository;
    @Autowired SubmissionVersionRepository submissionVersionRepository;
    @Autowired UserRepository userRepository;
    @Autowired WebFormAnswerRepository webFormAnswerRepository;
    @Autowired WebFormAttachmentRepository webFormAttachmentRepository;
    @Autowired WebFormSessionRepository webFormSessionRepository;
    @Autowired XFormRepository xFormRepository;

    @Test
    void everyRepositoryBean_isRegistered() {
        assertNotNull(appUserProjectRepository);
        assertNotNull(appUserRepository);
        assertNotNull(auditLogRepository);
        assertNotNull(auditLogDetailRepository);
        assertNotNull(entityItemPropertyRepository);
        assertNotNull(entityItemRepository);
        assertNotNull(entityListRepository);
        assertNotNull(formAttachmentRepository);
        assertNotNull(formExternalDatasetRepository);
        assertNotNull(formPublishHistoryRepository);
        assertNotNull(formVersionRepository);
        assertNotNull(projectRepository);
        assertNotNull(projectSettingsRepository);
        assertNotNull(referenceItemPropertyRepository);
        assertNotNull(referenceItemRelationshipRepository);
        assertNotNull(referenceItemRepository);
        assertNotNull(referenceListRepository);
        assertNotNull(refreshTokenRepository);
        assertNotNull(submissionAnswerRepository);
        assertNotNull(submissionAttachmentRepository);
        assertNotNull(submissionEventRepository);
        assertNotNull(submissionRepository);
        assertNotNull(submissionVersionRepository);
        assertNotNull(userRepository);
        assertNotNull(webFormAnswerRepository);
        assertNotNull(webFormAttachmentRepository);
        assertNotNull(webFormSessionRepository);
        assertNotNull(xFormRepository);
    }

    @Test
    void representativeQueries_executeAgainstH2Schema() {
        UUID id = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Instant now = Instant.now();

        assertDoesNotThrow(() -> appUserProjectRepository.findAssignedFormIdsByAppUserIdAndProjectId(id, secondId));
        assertDoesNotThrow(() -> appUserRepository.findByUsernameWithProjects("missing-user"));
        assertDoesNotThrow(() -> auditLogRepository.findByUserIdAndDateRange(id, now.minusSeconds(60), now.plusSeconds(60)));
        assertDoesNotThrow(() -> auditLogDetailRepository.findByAuditLogIdOrderByCreatedAtAsc(id));

        assertDoesNotThrow(() -> entityItemPropertyRepository.findByEntityItemIdOrderByPropertyNameAsc(id));
        assertDoesNotThrow(() -> entityItemRepository.findByEntityListIdAndEntityId(id, "missing-entity"));
        assertDoesNotThrow(() -> entityListRepository.findByProjectIdAndNameAndIsActiveTrue(id, "missing-list"));

        assertDoesNotThrow(() -> formAttachmentRepository.findByFormVersionIdAndFileName(id, "missing.png"));
        assertDoesNotThrow(() -> formExternalDatasetRepository.findByFormVersionIdAndDatasetId(id, "missing.csv"));
        assertDoesNotThrow(() -> formPublishHistoryRepository.findByActionOrderByPerformedAtDesc("PUBLISH", PageRequest.of(0, 1)));
        assertDoesNotThrow(() -> formVersionRepository.findByFormIdAndStatusInOrderByVersionDesc(id, List.of(FormStatus.PUBLISHED)));

        assertDoesNotThrow(() -> projectRepository.findByIdWithOwnerAndCreatedBy(id));
        assertDoesNotThrow(() -> projectSettingsRepository.findByProjectIdAndSettingKeyNative(id, "missing.setting"));

        assertDoesNotThrow(() -> referenceItemPropertyRepository.findByReferenceItemIdOrderByPropertyNameAsc(id));
        assertDoesNotThrow(() -> referenceItemRelationshipRepository.findByParentItemIdAndChildItemIdAndRelationshipType(id, secondId, "parent"));
        assertDoesNotThrow(() -> referenceItemRepository.findByReferenceListIdAndItemKey(id, "missing-item"));
        assertDoesNotThrow(() -> referenceListRepository.findByProjectIdAndNameIgnoreCase(id, "missing-list"));

        assertDoesNotThrow(() -> refreshTokenRepository.findValidByUserUsername("missing-user", now));

        assertDoesNotThrow(() -> submissionAnswerRepository.findBySubmissionIdAndRepeatPathAndRepeatIndex(id, "members", 0));
        assertDoesNotThrow(() -> submissionAnswerRepository.findBySubmissionIdAndValueText(id, "missing.jpg"));
        assertDoesNotThrow(() -> submissionAttachmentRepository.findBySubmissionId(id));
        assertDoesNotThrow(() -> submissionEventRepository.findBySubmissionIdAndEventType(id, "CREATED"));
        assertDoesNotThrow(() -> submissionRepository.findSubmissions(
                id, secondId, UUID.randomUUID(), "received", null, UUID.randomUUID(),
                now.minusSeconds(60), now.plusSeconds(60), PageRequest.of(0, 5)));
        assertDoesNotThrow(() -> submissionRepository.findSubmissions(
                id, secondId, UUID.randomUUID(), "received", "ODK Collect", UUID.randomUUID(),
                now.minusSeconds(60), now.plusSeconds(60), PageRequest.of(0, 5)));
        assertDoesNotThrow(() -> submissionRepository.findAttachmentBySubmissionIdAndId(id, secondId));
        assertDoesNotThrow(() -> submissionVersionRepository.findBySubmissionIdOrderByVersionNumberDesc(id));

        assertDoesNotThrow(() -> userRepository.findByUsernameWithRolesAndPermissions("missing-user"));

        assertDoesNotThrow(() -> webFormAnswerRepository.findBySessionAndQuestionAndRepeat(id, "name", null, null));
        assertDoesNotThrow(() -> webFormAttachmentRepository.findBySessionId(id));
        assertDoesNotThrow(() -> webFormSessionRepository.findByLastActivityAtBefore(OffsetDateTime.now()));

        assertDoesNotThrow(() -> xFormRepository.findPublishedByProjectIdAndFormIds(id, List.of(secondId)));
    }
}
