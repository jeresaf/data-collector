-- Preserve the original questionnaire version in the revision snapshot; do not
-- assume a correction uses the same version as its source submission.
ALTER TABLE submission_versions
    ADD COLUMN source_form_version_id UUID REFERENCES form_versions(id);

-- M3 additive schema: record corrections and periodic visits are DIFFERENT workflows.
-- No alteration/deletion of historical submission rows; existing Flyway V1 remains immutable.
CREATE TABLE record_work_items (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    source_submission_id UUID NOT NULL REFERENCES submissions(id),
    source_form_version_id UUID REFERENCES form_versions(id),
    source_instance_id VARCHAR(255) NOT NULL,
    target_form_version_id UUID NOT NULL REFERENCES form_versions(id),
    assigned_app_user_id UUID NOT NULL REFERENCES app_users(id),
    work_type VARCHAR(16) NOT NULL CHECK (work_type IN ('AMENDMENT', 'FOLLOW_UP')),
    state VARCHAR(16) NOT NULL DEFAULT 'READY' CHECK (state IN ('READY', 'COMPLETED', 'CANCELLED')),
    client_request_id VARCHAR(128) NOT NULL,
    visit_key VARCHAR(128),
    planned_for TIMESTAMP WITH TIME ZONE,
    completed_submission_id UUID UNIQUE REFERENCES submissions(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_record_work_idempotency UNIQUE(project_id, assigned_app_user_id, client_request_id),
    CONSTRAINT uq_record_visit UNIQUE(project_id, source_submission_id, work_type, visit_key),
    CONSTRAINT ck_record_work_completed CHECK
        ((state = 'COMPLETED' AND completed_submission_id IS NOT NULL)
          OR (state <> 'COMPLETED' AND completed_submission_id IS NULL))
);

CREATE INDEX idx_record_work_assignee ON record_work_items(project_id, assigned_app_user_id, state);
CREATE INDEX idx_record_work_source ON record_work_items(source_submission_id, created_at);
CREATE INDEX idx_record_work_due ON record_work_items(planned_for) WHERE state = 'READY';

-- Each visit or amendment has exactly one linked resulting submission.
-- A FOLLOW_UP is a new observation, never a replacement of an earlier submission.
CREATE TABLE record_links (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    source_submission_id UUID NOT NULL REFERENCES submissions(id),
    child_submission_id UUID NOT NULL UNIQUE REFERENCES submissions(id),
    work_item_id UUID NOT NULL UNIQUE REFERENCES record_work_items(id),
    link_type VARCHAR(16) NOT NULL CHECK (link_type IN ('AMENDMENT', 'FOLLOW_UP')),
    visit_key VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_record_links_not_self CHECK (source_submission_id <> child_submission_id)
);
CREATE INDEX idx_record_links_source ON record_links(project_id, source_submission_id, created_at);
