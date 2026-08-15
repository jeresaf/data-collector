package org.odk.platform.repository;

import org.odk.platform.domain.FormAttachment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FormAttachmentRepository extends JpaRepository<FormAttachment, UUID> {

    List<FormAttachment> findByFormVersionId(UUID formVersionId);

    Page<FormAttachment> findByFormVersionId(UUID formVersionId, Pageable pageable);

    Optional<FormAttachment> findByFormVersionIdAndFileName(UUID formVersionId, String fileName);

    List<FormAttachment> findByContentHash(String contentHash);

    @Query("SELECT fa FROM FormAttachment fa WHERE fa.formVersion.id = :formVersionId AND fa.isEncrypted = true")
    List<FormAttachment> findEncryptedByFormVersionId(@Param("formVersionId") UUID formVersionId);

    boolean existsByFormVersionIdAndFileName(UUID formVersionId, String fileName);

    long countByFormVersionId(UUID formVersionId);

    @Query("SELECT COALESCE(SUM(fa.fileSize), 0L) "
            + "FROM FormAttachment fa "
            + "WHERE fa.formVersion.id = :formVersionId")
    long sumFileSizeByFormVersionId(@Param("formVersionId") UUID formVersionId);
}