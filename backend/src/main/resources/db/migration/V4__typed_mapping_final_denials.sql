-- M3D4B2B1: an independent final authorizer can DENY a reviewer
-- recommendation. No positive mapping grant can be recorded by this schema.
-- V1, V2 and V3 remain immutable. Qualify Flyway upgrade before rollout.
CREATE TABLE typed_mapping_final_denials (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    work_item_id UUID NOT NULL REFERENCES record_work_items(id),
    recommendation_id UUID NOT NULL UNIQUE
        REFERENCES typed_mapping_review_recommendations(id),
    original_reviewer_user_id UUID NOT NULL REFERENCES users(id),
    final_authorizer_user_id UUID NOT NULL REFERENCES users(id),
    client_request_id VARCHAR(128) NOT NULL,
    reason_code VARCHAR(48) NOT NULL CHECK (reason_code IN
        ('INSUFFICIENT_EVIDENCE', 'SCHEMA_UNSAFE', 'CONSENT_UNCLEAR',
         'SOURCE_UNTRUSTED', 'POLICY_NOT_SATISFIED')),
    recommendation_evidence_sha256 VARCHAR(64) NOT NULL,
    denial_evidence_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_final_denial_independent_users
        CHECK (original_reviewer_user_id <> final_authorizer_user_id),
    CONSTRAINT uq_final_denial_request
        UNIQUE (work_item_id, final_authorizer_user_id, client_request_id)
);
CREATE INDEX idx_final_denial_project_work
    ON typed_mapping_final_denials(project_id, work_item_id, created_at);
-- No write/update/delete grant endpoint exists. This SQL table is not
-- immutable against privileged DBA changes; a signed independently anchored
-- ledger remains a separate release gate.
