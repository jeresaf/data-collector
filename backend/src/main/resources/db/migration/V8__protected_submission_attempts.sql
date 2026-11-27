-- U08 additive protected attempt registry; existing work is not backfilled.
-- A reservation is immutable and not evidence of receipt. Acceptance is written
-- in the same database transaction as work completion and submission persistence.
CREATE TABLE protected_submission_attempts (
    work_item_id UUID PRIMARY KEY REFERENCES record_work_items(id),
    attempt_id UUID NOT NULL UNIQUE,
    client_request_id UUID NOT NULL,
    project_id UUID NOT NULL REFERENCES projects(id),
    actor_id UUID NOT NULL REFERENCES app_users(id),
    source_submission_id UUID NOT NULL REFERENCES submissions(id),
    target_form_version_id UUID NOT NULL REFERENCES form_versions(id),
    form_id UUID NOT NULL REFERENCES xforms(id),
    instance_id VARCHAR(255) NOT NULL,
    wire_sha256 VARCHAR(64),
    accepted_submission_id UUID UNIQUE REFERENCES submissions(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_protected_attempt_instance UNIQUE(form_id, instance_id),
    CONSTRAINT ck_protected_attempt_accepted CHECK
        (accepted_submission_id IS NULL OR wire_sha256 IS NOT NULL)
);
