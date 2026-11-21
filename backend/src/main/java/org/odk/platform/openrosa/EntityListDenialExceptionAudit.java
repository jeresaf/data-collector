package org.odk.platform.openrosa;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Inactive security audit boundary for unexpected runtime failures.
 *
 * Writes a bounded UNAVAILABLE event on a separate transaction, then
 * propagates the original failure. Audit storage failure is also fatal.
 * Never includes exception messages, SQL, candidate bytes or credentials in
 * the immutable journal. Call ONLY around fallible application work, not
 * around ordinary denied-result logging (to avoid duplicate audit events).
 */
final class EntityListDenialExceptionAudit {
    private EntityListDenialExceptionAudit() {
    }

    static <T> T call(JdbcEntityListDenialEvidenceWriter writer,
            UUID reviewId, UUID actor,
            JdbcEntityListDenialEvidenceWriter.Operation operation,
            Supplier<T> action) {
        Objects.requireNonNull(writer);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(action);
        try {
            return action.get();
        } catch (RuntimeException originalFailure) {
            try {
                writer.record(reviewId, actor, operation,
                        JdbcEntityListDenialEvidenceWriter.Reason.UNAVAILABLE);
            } catch (RuntimeException auditFailure) {
                auditFailure.addSuppressed(originalFailure);
                throw auditFailure;
            }
            throw originalFailure;
        }
    }
}
