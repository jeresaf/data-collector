package org.odk.platform.repository;

import org.odk.platform.domain.Submission;
import org.odk.platform.domain.SubmissionAttachment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubmissionRepository extends JpaRepository<Submission, UUID> {

    @Query("SELECT s FROM Submission s WHERE s.form.id = :formId ORDER BY s.submittedAt DESC")
    List<Submission> findByFormIdOrderBySubmittedAtDesc(@Param("formId") UUID formId);

    @Query("SELECT s FROM Submission s WHERE s.form.id = :formId AND s.status = :status ORDER BY s.submittedAt DESC")
    List<Submission> findByFormIdAndStatusOrderBySubmittedAtDesc(@Param("formId") UUID formId, @Param("status") String status);

    @Query("SELECT s FROM Submission s WHERE s.instanceId = :instanceId")
    Optional<Submission> findByInstanceId(@Param("instanceId") String instanceId);

    @Query("SELECT s FROM Submission s WHERE s.form.id = :formId AND s.instanceId = :instanceId")
    Optional<Submission> findByFormIdAndInstanceId(@Param("formId") UUID formId, @Param("instanceId") String instanceId);

    @Query("SELECT s FROM Submission s WHERE s.submittedBy.id = :userId ORDER BY s.submittedAt DESC")
    List<Submission> findBySubmittedByIdOrderBySubmittedAtDesc(@Param("userId") UUID userId);

    @Query("SELECT s FROM Submission s WHERE s.form.project.id = :projectId ORDER BY s.submittedAt DESC")
    List<Submission> findByProjectIdOrderBySubmittedAtDesc(@Param("projectId") UUID projectId);

    @Query("SELECT s FROM Submission s WHERE s.formVersion.id = :formVersionId ORDER BY s.submittedAt DESC")
    List<Submission> findByFormVersionIdOrderBySubmittedAtDesc(@Param("formVersionId") UUID formVersionId);

    @Query("SELECT COUNT(s) FROM Submission s WHERE s.form.id = :formId")
    long countByFormId(@Param("formId") UUID formId);

    @Query("SELECT COUNT(s) FROM Submission s WHERE s.form.project.id = :projectId")
    long countByProjectId(@Param("projectId") UUID projectId);

    @Query("SELECT s FROM Submission s JOIN FETCH s.form WHERE s.id = :submissionId")
    Optional<Submission> findByIdWithForm(@Param("submissionId") UUID submissionId);

    @Query("SELECT s FROM Submission s JOIN FETCH s.form JOIN FETCH s.formVersion WHERE s.id = :submissionId")
    Optional<Submission> findByIdWithFormAndFormVersion(@Param("submissionId") UUID submissionId);

    @Query("SELECT s FROM Submission s WHERE s.form.id = :formId ORDER BY s.submittedAt DESC")
    List<Submission> findByFormId(@Param("formId") UUID formId);

    @Query("SELECT s FROM Submission s WHERE s.form.id = :formId ORDER BY s.submittedAt DESC")
    List<Submission> findByFormId(@Param("formId") UUID formId, Pageable pageable);

    /**
     * Preserve the existing offset/limit API while delegating pagination to
     * Spring Data through a recognized Pageable parameter.
     *
     * Unlike PageRequest, this supports arbitrary row offsets (for example,
     * offset=15, limit=10) without converting the offset into a page number.
     */
    default List<Submission> findByFormId(UUID formId, int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }

        return findByFormId(formId, new OffsetLimitPageable(offset, limit));
    }

    final class OffsetLimitPageable implements Pageable {

        private final long offset;
        private final int limit;

        OffsetLimitPageable(long offset, int limit) {
            if (offset < 0) {
                throw new IllegalArgumentException("offset must be >= 0");
            }
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be > 0");
            }
            this.offset = offset;
            this.limit = limit;
        }

        @Override
        public int getPageNumber() {
            return (int) (offset / limit);
        }

        @Override
        public int getPageSize() {
            return limit;
        }

        @Override
        public long getOffset() {
            return offset;
        }

        @Override
        public Sort getSort() {
            return Sort.unsorted();
        }

        @Override
        public Pageable next() {
            return new OffsetLimitPageable(offset + limit, limit);
        }

        @Override
        public Pageable previousOrFirst() {
            return hasPrevious()
                    ? new OffsetLimitPageable(Math.max(0, offset - limit), limit)
                    : first();
        }

        @Override
        public Pageable first() {
            return new OffsetLimitPageable(0, limit);
        }

        @Override
        public Pageable withPage(int pageNumber) {
            if (pageNumber < 0) {
                throw new IllegalArgumentException("pageNumber must be >= 0");
            }
            return new OffsetLimitPageable((long) pageNumber * limit, limit);
        }

        @Override
        public boolean hasPrevious() {
            return offset > 0;
        }
    }

    @Query("SELECT s FROM Submission s WHERE s.formVersion.id = :formVersionId ORDER BY s.submittedAt DESC")
    List<Submission> findByFormVersionId(@Param("formVersionId") UUID formVersionId);

    @Query("SELECT s FROM Submission s WHERE s.status = :status ORDER BY s.submittedAt DESC")
    List<Submission> findByStatus(@Param("status") String status);

    @Query("SELECT COUNT(s) FROM Submission s WHERE s.form.id = :formId AND s.status = :status")
    long countByFormIdAndStatus(@Param("formId") UUID formId, @Param("status") String status);

    /**
     * Database-backed submission filtering for fields represented as relational columns.
     *
     * Source/client filtering is intentionally handled by the public default methods below
     * because metadata is JSONB in PostgreSQL but CLOB/JSON-compatible data in H2. Keeping
     * vendor-specific JSON functions out of JPQL makes this repository portable across both.
     */
    @Query(value = "SELECT s FROM Submission s " +
           "JOIN FETCH s.form f " +
           "LEFT JOIN FETCH s.formVersion fv " +
           "LEFT JOIN FETCH s.submittedBy sb " +
           "LEFT JOIN FETCH s.reviewedBy rb " +
           "WHERE (:projectId IS NULL OR f.project.id = :projectId) " +
           "AND (:formId IS NULL OR f.id = :formId) " +
           "AND (:formVersionId IS NULL OR fv.id = :formVersionId) " +
           "AND (:status IS NULL OR s.status = :status) " +
           "AND (:submittedById IS NULL OR sb.id = :submittedById) " +
           "AND (:dateFrom IS NULL OR s.submittedAt >= :dateFrom) " +
           "AND (:dateTo IS NULL OR s.submittedAt <= :dateTo) " +
           "ORDER BY s.submittedAt DESC",
           countQuery = "SELECT COUNT(s) FROM Submission s " +
           "JOIN s.form f " +
           "LEFT JOIN s.formVersion fv " +
           "LEFT JOIN s.submittedBy sb " +
           "WHERE (:projectId IS NULL OR f.project.id = :projectId) " +
           "AND (:formId IS NULL OR f.id = :formId) " +
           "AND (:formVersionId IS NULL OR fv.id = :formVersionId) " +
           "AND (:status IS NULL OR s.status = :status) " +
           "AND (:submittedById IS NULL OR sb.id = :submittedById) " +
           "AND (:dateFrom IS NULL OR s.submittedAt >= :dateFrom) " +
           "AND (:dateTo IS NULL OR s.submittedAt <= :dateTo)")
    Page<Submission> findSubmissionsBase(
            @Param("projectId") UUID projectId,
            @Param("formId") UUID formId,
            @Param("formVersionId") UUID formVersionId,
            @Param("status") String status,
            @Param("submittedById") UUID submittedById,
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo,
            Pageable pageable);

    @Query("SELECT COUNT(s) FROM Submission s " +
           "JOIN s.form f " +
           "LEFT JOIN s.formVersion fv " +
           "LEFT JOIN s.submittedBy sb " +
           "WHERE (:projectId IS NULL OR f.project.id = :projectId) " +
           "AND (:formId IS NULL OR f.id = :formId) " +
           "AND (:formVersionId IS NULL OR fv.id = :formVersionId) " +
           "AND (:status IS NULL OR s.status = :status) " +
           "AND (:submittedById IS NULL OR sb.id = :submittedById) " +
           "AND (:dateFrom IS NULL OR s.submittedAt >= :dateFrom) " +
           "AND (:dateTo IS NULL OR s.submittedAt <= :dateTo)")
    long countSubmissionsBase(
            @Param("projectId") UUID projectId,
            @Param("formId") UUID formId,
            @Param("formVersionId") UUID formVersionId,
            @Param("status") String status,
            @Param("submittedById") UUID submittedById,
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo);

    /**
     * Find submissions with the existing public filter contract.
     *
     * When source is supplied, candidates are filtered against the deserialized metadata map
     * and then paginated. This preserves exact source matching without database-specific JSON SQL.
     */
    default Page<Submission> findSubmissions(
            UUID projectId,
            UUID formId,
            UUID formVersionId,
            String status,
            String source,
            UUID submittedById,
            Instant dateFrom,
            Instant dateTo,
            Pageable pageable) {

        if (source == null || source.isBlank()) {
            return findSubmissionsBase(
                    projectId, formId, formVersionId, status, submittedById, dateFrom, dateTo, pageable);
        }

        List<Submission> filtered = findSubmissionsBase(
                projectId, formId, formVersionId, status, submittedById, dateFrom, dateTo, Pageable.unpaged())
                .getContent()
                .stream()
                .filter(submission -> sourceMatches(submission, source))
                .toList();

        if (pageable == null || pageable.isUnpaged()) {
            return new PageImpl<>(filtered);
        }

        long rawOffset = pageable.getOffset();
        int start = rawOffset >= filtered.size() ? filtered.size() : (int) rawOffset;
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        return new PageImpl<>(filtered.subList(start, end), pageable, filtered.size());
    }

    default long countSubmissions(
            UUID projectId,
            UUID formId,
            UUID formVersionId,
            String status,
            String source,
            UUID submittedById,
            Instant dateFrom,
            Instant dateTo) {

        if (source == null || source.isBlank()) {
            return countSubmissionsBase(
                    projectId, formId, formVersionId, status, submittedById, dateFrom, dateTo);
        }

        return findSubmissionsBase(
                projectId, formId, formVersionId, status, submittedById, dateFrom, dateTo, Pageable.unpaged())
                .getContent()
                .stream()
                .filter(submission -> sourceMatches(submission, source))
                .count();
    }

    private static boolean sourceMatches(Submission submission, String source) {
        if (submission == null || submission.getMetadata() == null) {
            return false;
        }
        Object storedSource = submission.getMetadata().get("sourceClient");
        if (storedSource == null) {
            // Backward compatibility for any records written with the older key.
            storedSource = submission.getMetadata().get("source");
        }
        return storedSource != null && source.equals(storedSource.toString());
    }

    /**
     * Find submission by ID with all details fetched.
     */
    @Query("SELECT s FROM Submission s " +
           "JOIN FETCH s.form f " +
           "LEFT JOIN FETCH s.formVersion fv " +
           "LEFT JOIN FETCH s.submittedBy sb " +
           "LEFT JOIN FETCH s.reviewedBy rb " +
           "LEFT JOIN FETCH s.attachmentsList a " +
           "LEFT JOIN FETCH s.answers ans " +
           "LEFT JOIN FETCH s.events e " +
           "WHERE s.id = :submissionId")
    Optional<Submission> findByIdWithDetails(@Param("submissionId") UUID submissionId);

    /**
     * Find attachment by submission ID and attachment ID.
     */
    @Query("SELECT a FROM SubmissionAttachment a " +
           "WHERE a.submission.id = :submissionId AND a.id = :attachmentId")
    Optional<SubmissionAttachment> findAttachmentBySubmissionIdAndId(
            @Param("submissionId") UUID submissionId,
            @Param("attachmentId") UUID attachmentId);
}
