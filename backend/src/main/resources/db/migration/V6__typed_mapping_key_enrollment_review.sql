-- M3D4C2B2A: untrusted proposed PUBLIC Ed25519 keys and separate reviews.
-- Absolutely no registration into typed_mapping_trust_keys and no grants.
-- Additive V6; prior production Flyway V1-V5 remain immutable.
CREATE TABLE typed_mapping_key_enrollment_requests (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    proposed_key_id VARCHAR(64) NOT NULL,
    public_key_spki BYTEA NOT NULL,
    spki_sha256 VARCHAR(64) NOT NULL,
    requester_user_id UUID NOT NULL REFERENCES users(id),
    client_request_id VARCHAR(128) NOT NULL,
    purpose VARCHAR(48) NOT NULL CHECK (purpose IN
        ('NEW_PROJECT_PILOT','ROTATION_REVIEW','COMPROMISE_REPLACEMENT')),
    request_evidence_sha256 VARCHAR(64) NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_untrusted_key_request_idempotency
        UNIQUE (project_id, requester_user_id, client_request_id),
    CONSTRAINT uq_untrusted_key_project_id UNIQUE (project_id, proposed_key_id)
);
CREATE INDEX idx_untrusted_key_enrollment_project
    ON typed_mapping_key_enrollment_requests(project_id, requested_at);

CREATE TABLE typed_mapping_key_enrollment_reviews (
    request_id UUID PRIMARY KEY REFERENCES typed_mapping_key_enrollment_requests(id),
    project_id UUID NOT NULL REFERENCES projects(id),
    reviewer_user_id UUID NOT NULL REFERENCES users(id),
    disposition VARCHAR(48) NOT NULL CHECK (disposition IN
        ('REJECTED', 'REQUIRES_OFFLINE_KEY_CEREMONY')),
    request_evidence_sha256 VARCHAR(64) NOT NULL,
    review_evidence_sha256 VARCHAR(64) NOT NULL,
    reviewed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_untrusted_key_reviews_project
    ON typed_mapping_key_enrollment_reviews(project_id, reviewed_at);
-- Not an immutable or anchored audit. No backend path copies these rows into
-- the trusted key registry. No private key, historical answers, or token.
