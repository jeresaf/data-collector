package org.odk.platform.repository;

import org.odk.platform.domain.WebFormAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WebFormAttachmentRepository extends JpaRepository<WebFormAttachment, UUID> {

    List<WebFormAttachment> findBySessionId(UUID sessionId);

    List<WebFormAttachment> findByAnswerId(UUID answerId);

    void deleteBySessionId(UUID sessionId);
}