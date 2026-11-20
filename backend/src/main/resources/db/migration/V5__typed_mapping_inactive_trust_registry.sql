-- M3D4C2A: inactive trust and revocation registry, NOT an issuance system.
-- No keys or grants are seeded and no API can insert or activate a grant.
-- Production Flyway V1..V4 are immutable. Upgrade rehearsal is a release gate.
CREATE TABLE typed_mapping_trust_keys (
    key_id VARCHAR(64) PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    algorithm VARCHAR(16) NOT NULL DEFAULT 'Ed25519'
        CHECK (algorithm = 'Ed25519'),
    public_key_spki BYTEA NOT NULL,
    valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_until TIMESTAMP WITH TIME ZONE NOT NULL,
    registered_by UUID NOT NULL REFERENCES users(id),
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_typed_trust_key_window CHECK (valid_until > valid_from),
    CONSTRAINT uq_typed_trust_project_key UNIQUE (project_id, key_id)
);
CREATE TABLE typed_mapping_signed_grant_registry (
    grant_id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    work_item_id UUID NOT NULL REFERENCES record_work_items(id),
    recommendation_id UUID NOT NULL UNIQUE
        REFERENCES typed_mapping_review_recommendations(id),
    signing_key_id VARCHAR(64) NOT NULL
        REFERENCES typed_mapping_trust_keys(key_id),
    source_submission_id UUID NOT NULL REFERENCES submissions(id),
    target_form_version_id UUID NOT NULL REFERENCES form_versions(id),
    original_reviewer_id UUID NOT NULL REFERENCES users(id),
    final_signer_id UUID NOT NULL REFERENCES users(id),
    source_instance_id VARCHAR(255) NOT NULL,
    source_xml_sha256 VARCHAR(64) NOT NULL,
    source_xform_sha256 VARCHAR(64) NOT NULL,
    target_xform_sha256 VARCHAR(64) NOT NULL,
    recommendation_evidence_sha256 VARCHAR(64) NOT NULL,
    canonical_envelope_sha256 VARCHAR(64) NOT NULL,
    signature_sha256 VARCHAR(64) NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    not_before TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_typed_grant_independent_signer
        CHECK (original_reviewer_id <> final_signer_id),
    CONSTRAINT ck_typed_grant_time
        CHECK (not_before >= issued_at AND expires_at > not_before),
    CONSTRAINT uq_typed_grant_work_recommendation
        UNIQUE (project_id, work_item_id, recommendation_id)
);
CREATE INDEX idx_typed_grant_work ON typed_mapping_signed_grant_registry
    (project_id, work_item_id, expires_at);
CREATE TABLE typed_mapping_registered_grant_fields (
    grant_id UUID NOT NULL REFERENCES typed_mapping_signed_grant_registry(grant_id),
    field_path VARCHAR(255) NOT NULL,
    native_type VARCHAR(16) NOT NULL CHECK
        (native_type IN ('TEXT','INTEGER','DECIMAL','DATE')),
    PRIMARY KEY (grant_id, field_path)
);
CREATE TABLE typed_mapping_grant_revocations (
    grant_id UUID PRIMARY KEY REFERENCES typed_mapping_signed_grant_registry(grant_id),
    revoked_by UUID NOT NULL REFERENCES users(id),
    reason VARCHAR(64) NOT NULL CHECK
        (reason IN ('SECURITY','CONSENT_WITHDRAWN','SCHEMA_DRIFT',
         'WORK_CANCELLED','SOURCE_CHANGED','SIGNER_REVOKED')),
    revoked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE typed_mapping_key_revocations (
    key_id VARCHAR(64) PRIMARY KEY REFERENCES typed_mapping_trust_keys(key_id),
    revoked_by UUID NOT NULL REFERENCES users(id),
    reason VARCHAR(64) NOT NULL CHECK
        (reason IN ('KEY_COMPROMISE','KEY_ROTATION','SECURITY','OPERATOR_REQUEST')),
    revoked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- All tables contain metadata and PUBLIC verification keys only, no private
-- keys or historical answer values. This is not DBA-immutable or anchored.
-- Applications have NO grant/key registration or revocation-writing endpoints.
