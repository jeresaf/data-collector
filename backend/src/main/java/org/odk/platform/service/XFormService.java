package org.odk.platform.service;

import lombok.RequiredArgsConstructor;
import org.odk.platform.domain.XForm;
import org.odk.platform.repository.XFormRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class XFormService {

    private final XFormRepository xFormRepository;

    @Transactional(readOnly = true)
    public Optional<XForm> findById(UUID id) {
        return xFormRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<XForm> findByIdWithProject(UUID id) {
        return xFormRepository.findByIdWithProjectFetch(id);
    }

    @Transactional(readOnly = true)
    public Optional<XForm> findByIdAndProjectId(UUID formId, UUID projectId) {
        return xFormRepository.findByIdAndProjectId(formId, projectId);
    }

    @Transactional(readOnly = true)
    public Optional<XForm> findByXmlnsAndProjectId(String xmlns, UUID projectId) {
        return xFormRepository.findByXmlnsAndProjectId(xmlns, projectId);
    }

    @Transactional(readOnly = true)
    public List<XForm> findByProjectIdAndIsActiveTrue(UUID projectId) {
        return xFormRepository.findByProjectIdAndIsActiveTrue(projectId);
    }

    @Transactional(readOnly = true)
    public Page<XForm> findByProjectIdAndIsActiveTrue(UUID projectId, Pageable pageable) {
        return xFormRepository.findByProjectIdAndIsActiveTrue(projectId, pageable);
    }

    @Transactional(readOnly = true)
    public List<XForm> findPublishedByProjectId(UUID projectId) {
        return xFormRepository.findPublishedByProjectId(projectId);
    }

    public XForm save(XForm form) {
        return xFormRepository.save(form);
    }

    public void deleteById(UUID id) {
        xFormRepository.deleteById(id);
    }

    public boolean existsById(UUID id) {
        return xFormRepository.existsById(id);
    }
}