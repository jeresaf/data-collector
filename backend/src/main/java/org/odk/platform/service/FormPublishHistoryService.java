package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.odk.platform.domain.FormPublishHistory;
import org.odk.platform.domain.FormStatus;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.domain.User;
import org.odk.platform.repository.FormPublishHistoryRepository;
import org.odk.platform.repository.FormVersionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormPublishHistoryService {

    private final FormPublishHistoryRepository publishHistoryRepository;
    private final FormVersionRepository formVersionRepository;

    public Page<FormPublishHistory> getPublishHistoryByFormVersion(FormVersion formVersion, Pageable pageable) {
        return publishHistoryRepository.findByFormVersionOrderByPerformedAtDesc(formVersion, pageable);
    }

    public List<FormPublishHistory> getAllPublishHistoryByFormVersion(FormVersion formVersion) {
        return publishHistoryRepository.findByFormVersionOrderByPerformedAtDesc(formVersion);
    }

    public Page<FormPublishHistory> getPublishHistoryByForm(org.odk.platform.domain.XForm form, Pageable pageable) {
        return publishHistoryRepository.findByFormOrderByPerformedAtDesc(form, pageable);
    }

    public Page<FormPublishHistory> getPublishHistoryByUser(User user, Pageable pageable) {
        return publishHistoryRepository.findByPerformedByOrderByPerformedAtDesc(user, pageable);
    }

    public Page<FormPublishHistory> getPublishHistoryByAction(String action, Pageable pageable) {
        return publishHistoryRepository.findByActionOrderByPerformedAtDesc(action, pageable);
    }

    public Optional<FormPublishHistory> getLatestPublishHistory(FormVersion formVersion) {
        return publishHistoryRepository.findTopByFormVersionOrderByPerformedAtDesc(formVersion);
    }

    @Transactional
    public FormPublishHistory recordPublishAction(FormVersion formVersion, String action, User performedBy, FormVersion previousVersion, String notes) {
        FormPublishHistory history = FormPublishHistory.builder()
                .formVersion(formVersion)
                .action(action)
                .previousVersion(previousVersion)
                .performedBy(performedBy)
                .notes(notes)
                .build();
        
        // Update FormVersion status and timestamps based on action
        updateFormVersionStatus(formVersion, action, performedBy);
        
        return publishHistoryRepository.save(history);
    }

    @Transactional
    public FormPublishHistory recordPublish(FormVersion formVersion, User performedBy, String notes) {
        return recordPublishAction(formVersion, "PUBLISHED", performedBy, null, notes);
    }

    @Transactional
    public FormPublishHistory recordRepublish(FormVersion formVersion, FormVersion previousVersion, User performedBy, String notes) {
        return recordPublishAction(formVersion, "REPUBLISHED", performedBy, previousVersion, notes);
    }

    @Transactional
    public FormPublishHistory recordDeprecate(FormVersion formVersion, User performedBy, String notes) {
        return recordPublishAction(formVersion, "DEPRECATED", performedBy, null, notes);
    }

    @Transactional
    public FormPublishHistory recordArchive(FormVersion formVersion, User performedBy, String notes) {
        return recordPublishAction(formVersion, "ARCHIVED", performedBy, null, notes);
    }

    @Transactional
    public FormPublishHistory recordValidate(FormVersion formVersion, User performedBy, String notes) {
        return recordPublishAction(formVersion, "VALIDATED", performedBy, null, notes);
    }

    private void updateFormVersionStatus(FormVersion formVersion, String action, User performedBy) {
        Instant now = Instant.now();
        switch (action) {
            case "VALIDATED":
                formVersion.setStatus(FormStatus.VALIDATED);
                formVersion.setValidatedAt(now);
                formVersion.setValidatedBy(performedBy);
                break;
            case "PUBLISHED":
            case "REPUBLISHED":
                formVersion.setStatus(FormStatus.PUBLISHED);
                formVersion.setPublishedAt(now);
                formVersion.setPublishedBy(performedBy);
                formVersion.setIsPublished(true);
                formVersion.setIsLatest(true);
                break;
            case "DEPRECATED":
                formVersion.setStatus(FormStatus.DEPRECATED);
                formVersion.setDeprecatedAt(now);
                formVersion.setDeprecatedBy(performedBy);
                formVersion.setIsPublished(false);
                formVersion.setIsLatest(false);
                break;
            case "ARCHIVED":
                formVersion.setStatus(FormStatus.ARCHIVED);
                formVersion.setArchivedAt(now);
                formVersion.setArchivedBy(performedBy);
                formVersion.setIsPublished(false);
                formVersion.setIsLatest(false);
                break;
        }
        formVersionRepository.save(formVersion);
    }
}