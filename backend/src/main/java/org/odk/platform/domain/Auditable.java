package org.odk.platform.domain;

import java.time.Instant;
import java.util.UUID;

public interface Auditable {
    UUID getId();
    Instant getCreatedAt();
    Instant getUpdatedAt();
    UUID getCreatedBy();
    UUID getUpdatedBy();
    void setCreatedAt(Instant createdAt);
    void setUpdatedAt(Instant updatedAt);
    void setCreatedBy(UUID createdBy);
    void setUpdatedBy(UUID updatedBy);
}