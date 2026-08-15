package org.odk.platform.repository;

import org.odk.platform.domain.FormPublishHistory;
import org.odk.platform.domain.FormVersion;
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
public interface FormPublishHistoryRepository extends JpaRepository<FormPublishHistory, UUID> {

    @Query("SELECT fph FROM FormPublishHistory fph WHERE fph.formVersion = :formVersion ORDER BY fph.performedAt DESC")
    List<FormPublishHistory> findByFormVersionOrderByPerformedAtDesc(@Param("formVersion") FormVersion formVersion);

    @Query("SELECT fph FROM FormPublishHistory fph WHERE fph.formVersion = :formVersion ORDER BY fph.performedAt DESC")
    Page<FormPublishHistory> findByFormVersionOrderByPerformedAtDesc(@Param("formVersion") FormVersion formVersion, Pageable pageable);

    @Query("SELECT fph FROM FormPublishHistory fph WHERE fph.performedBy = :user ORDER BY fph.performedAt DESC")
    Page<FormPublishHistory> findByPerformedByOrderByPerformedAtDesc(@Param("user") org.odk.platform.domain.User user, Pageable pageable);

    @Query("SELECT fph FROM FormPublishHistory fph WHERE fph.action = :action ORDER BY fph.performedAt DESC")
    Page<FormPublishHistory> findByActionOrderByPerformedAtDesc(@Param("action") String action, Pageable pageable);

    @Query("SELECT fph FROM FormPublishHistory fph WHERE fph.formVersion.form = :form ORDER BY fph.performedAt DESC")
    Page<FormPublishHistory> findByFormOrderByPerformedAtDesc(@Param("form") org.odk.platform.domain.XForm form, Pageable pageable);

    Optional<FormPublishHistory> findTopByFormVersionOrderByPerformedAtDesc(FormVersion formVersion);
}