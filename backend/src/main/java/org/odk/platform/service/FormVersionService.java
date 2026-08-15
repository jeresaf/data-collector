package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.odk.platform.domain.FormStatus;
import org.odk.platform.domain.FormVersion;
import org.odk.platform.repository.FormVersionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormVersionService {

    private final FormVersionRepository formVersionRepository;

    public Optional<FormVersion> findById(UUID id) {
        return formVersionRepository.findById(id);
    }

    public Optional<FormVersion> findByIdWithForm(UUID id) {
        return formVersionRepository.findByIdWithFormFetch(id);
    }

    public List<FormVersion> findByFormIdOrderByVersionDesc(UUID formId) {
        return formVersionRepository.findByFormIdOrderByVersionDesc(formId);
    }

    public List<FormVersion> findPublishedByFormIdOrderByVersionDesc(UUID formId) {
        return formVersionRepository.findPublishedByFormIdOrderByVersionDesc(formId);
    }

    public Optional<FormVersion> findLatestByFormId(UUID formId) {
        return formVersionRepository.findLatestByFormId(formId);
    }

    public Optional<FormVersion> findByFormIdAndVersion(UUID formId, String version) {
        return formVersionRepository.findByFormIdAndVersion(formId, version);
    }

    public List<FormVersion> findByFormIdAndStatusOrderByVersionDesc(UUID formId, FormStatus status) {
        return formVersionRepository.findByFormIdAndStatusOrderByVersionDesc(formId, status);
    }

    public Page<FormVersion> findByFormIdAndStatusOrderByVersionDesc(UUID formId, FormStatus status, Pageable pageable) {
        return formVersionRepository.findByFormIdAndStatusOrderByVersionDesc(formId, status, pageable);
    }

    public Page<FormVersion> findByStatusOrderByCreatedAtDesc(FormStatus status, Pageable pageable) {
        return formVersionRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
    }

    public List<FormVersion> findByFormIdAndStatusInOrderByVersionDesc(UUID formId, List<FormStatus> statuses) {
        return formVersionRepository.findByFormIdAndStatusInOrderByVersionDesc(formId, statuses);
    }

    public Optional<FormVersion> findByFormIdAndIsLatestTrue(UUID formId) {
        return formVersionRepository.findByFormIdAndIsLatestTrue(formId);
    }

    @Transactional
    public FormVersion save(FormVersion formVersion) {
        return formVersionRepository.save(formVersion);
    }

    @Transactional
    public void deleteById(UUID id) {
        formVersionRepository.deleteById(id);
    }

    public boolean existsById(UUID id) {
        return formVersionRepository.existsById(id);
    }
}
