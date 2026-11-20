-- M3D4B2A: append-only API evidence of independent web reviewer recommendations.
-- These are NOT mapping grants. V1 and V2 are immutable.
-- This migration requires a disposable PostgreSQL 15 qualification and
-- production Flyway-history rehearsal before any deployment.
CREATE TABLE typed_mapping_review_recommendations (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    work_item_id UUID NOT NULL REFERENCES record_work_items(id),
    reviewer_user_id UUID NOT NULL REFERENCES users(id),
    client_request_id VARCHAR(128) NOT NULL,
    reason_code VARCHAR(48) NOT NULL
        CHECK (reason_code IN
            ('REENTER_REQUIRED', 'EXPLICIT_RULE_REVIEW_NEEDED', 'DO_NOT_TRANSFER')),
    source_submission_id UUID NOT NULL REFERENCES submissions(id),
    source_instance_id VARCHAR(255) NOT NULL,
    target_form_version_id UUID NOT NULL REFERENCES form_versions(id),
    source_xml_sha256 VARCHAR(64) NOT NULL,
    source_xform_sha256 VARCHAR(64) NOT NULL,
    target_xform_sha256 VARCHAR(64) NOT NULL,
    evidence_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_typed_recommendation_idempotency
        UNIQUE (work_item_id, reviewer_user_id, client_request_id)
);
CREATE INDEX idx_typed_recommendations_work
    ON typed_mapping_review_recommendations(project_id, work_item_id, created_at);
CREATE INDEX idx_typed_recommendations_reviewer
    ON typed_mapping_review_recommendations(reviewer_user_id, created_at);

CREATE TABLE typed_mapping_review_fields (
    recommendation_id UUID NOT NULL
        REFERENCES typed_mapping_review_recommendations(id),
    target_path VARCHAR(255) NOT NULL,
    disposition VARCHAR(40) NOT NULL CHECK
        (disposition IN ('REENTER', 'LEAVE_EMPTY', 'ESCALATE_FOR_RULE_REVIEW')),
    PRIMARY KEY (recommendation_id, target_path)
);
-- No update/delete/authorization endpoint is added. Database admins and
-- privileged data migrations can still mutate these rows; a cryptographically
-- signed authority ledger and database immutability enforcement are separate
-- release gates, not provided by this recommendation-only milestone.
