-- Test-only PostgreSQL ledger schema candidate.
-- This is NOT a Flyway migration and is never installed by application startup.
-- Isolated CI test connection sets search_path to a disposable schema.
CREATE TABLE entity_list_snapshot_history (
    project_id UUID NOT NULL,
    form_id UUID NOT NULL,
    filename VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    media_type VARCHAR(32) NOT NULL CHECK (media_type IN ('entityList', 'approvalEntityList')),
    md5 VARCHAR(32) NOT NULL CHECK (md5 ~ '^[0-9a-f]{32}$'),
    sha256 VARCHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    row_count BIGINT NOT NULL CHECK (row_count > 0),
    payload BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, form_id, filename, revision),
    CONSTRAINT uq_entity_list_history_exact_digest
        UNIQUE (project_id, form_id, filename, revision, sha256),
    CHECK (filename ~ '^[A-Za-z0-9][A-Za-z0-9_-]*[.]csv$')
);
-- split
CREATE TABLE entity_list_snapshot_heads (
    project_id UUID NOT NULL,
    form_id UUID NOT NULL,
    filename VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    sha256 VARCHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (project_id, form_id, filename),
    CONSTRAINT fk_entity_list_head_exact_history
        FOREIGN KEY (project_id, form_id, filename, revision, sha256)
        REFERENCES entity_list_snapshot_history (project_id, form_id, filename, revision, sha256)
        DEFERRABLE INITIALLY DEFERRED
);
-- split
CREATE FUNCTION deny_entity_list_history_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Entity List snapshot history is append-only';
END
$$ LANGUAGE plpgsql;
-- split
CREATE TRIGGER entity_list_history_immutable
BEFORE UPDATE OR DELETE ON entity_list_snapshot_history
FOR EACH ROW EXECUTE FUNCTION deny_entity_list_history_mutation();

-- split
-- Test-only candidate audit; no production migration.
CREATE TABLE entity_list_snapshot_audit (
    actor_id UUID NOT NULL,
    project_id UUID NOT NULL,
    form_id UUID NOT NULL,
    filename VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('INITIALIZED', 'APPENDED')),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, form_id, filename, revision, event_type),
    CONSTRAINT fk_entity_list_audit_exact_history
        FOREIGN KEY (project_id, form_id, filename, revision, sha256)
        REFERENCES entity_list_snapshot_history(project_id, form_id, filename, revision, sha256)
);
-- split
CREATE TRIGGER entity_list_audit_immutable
BEFORE UPDATE OR DELETE ON entity_list_snapshot_audit
FOR EACH ROW EXECUTE FUNCTION deny_entity_list_history_mutation();

-- split
-- Disposable review register; not a production Flyway migration.
CREATE TABLE entity_list_removal_reviews (
    id UUID PRIMARY KEY,
    requested_by UUID NOT NULL,
    project_id UUID NOT NULL,
    form_id UUID NOT NULL,
    filename VARCHAR(255) NOT NULL,
    expected_revision BIGINT NOT NULL CHECK (expected_revision > 0),
    expected_sha256 VARCHAR(64) NOT NULL CHECK (expected_sha256 ~ '^[0-9a-f]{64}$'),
    candidate_revision BIGINT NOT NULL CHECK (candidate_revision = expected_revision + 1),
    candidate_sha256 VARCHAR(64) NOT NULL CHECK (candidate_sha256 ~ '^[0-9a-f]{64}$'),
    missing_ids TEXT NOT NULL CHECK (length(missing_ids) > 0),
    justification VARCHAR(2000) NOT NULL CHECK (length(btrim(justification)) > 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','APPROVED','REJECTED')),
    reviewed_by UUID,
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (
        (state='PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL)
        OR (state IN ('APPROVED','REJECTED') AND reviewed_by IS NOT NULL
            AND reviewed_by <> requested_by AND reviewed_at IS NOT NULL)
    ),
    FOREIGN KEY (project_id, form_id, filename, expected_revision, expected_sha256)
        REFERENCES entity_list_snapshot_history(project_id, form_id, filename, revision, sha256)
);
-- split
CREATE FUNCTION prevent_entity_list_review_evidence_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.requested_by IS DISTINCT FROM NEW.requested_by
       OR OLD.project_id IS DISTINCT FROM NEW.project_id
       OR OLD.form_id IS DISTINCT FROM NEW.form_id
       OR OLD.filename IS DISTINCT FROM NEW.filename
       OR OLD.expected_revision IS DISTINCT FROM NEW.expected_revision
       OR OLD.expected_sha256 IS DISTINCT FROM NEW.expected_sha256
       OR OLD.candidate_revision IS DISTINCT FROM NEW.candidate_revision
       OR OLD.candidate_sha256 IS DISTINCT FROM NEW.candidate_sha256
       OR OLD.missing_ids IS DISTINCT FROM NEW.missing_ids
       OR OLD.justification IS DISTINCT FROM NEW.justification
       OR OLD.created_at IS DISTINCT FROM NEW.created_at
       OR OLD.state <> 'PENDING'
       OR NEW.state NOT IN ('APPROVED','REJECTED')
       OR NEW.reviewed_by IS NULL
       OR NEW.reviewed_by = OLD.requested_by
       OR NEW.reviewed_at IS NULL THEN
        RAISE EXCEPTION 'Removal review evidence is immutable and decision is final';
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;
-- split
CREATE TRIGGER entity_list_review_evidence_immutable
BEFORE UPDATE ON entity_list_removal_reviews
FOR EACH ROW EXECUTE FUNCTION prevent_entity_list_review_evidence_mutation();
-- split
CREATE TRIGGER entity_list_review_no_delete
BEFORE DELETE ON entity_list_removal_reviews
FOR EACH ROW EXECUTE FUNCTION deny_entity_list_history_mutation();

-- split
-- Immutable one-use validation receipt. NEVER a deletion/publish authority.
-- Disposable test schema only; not a production migration.
CREATE TABLE entity_list_removal_validation_seals (
    review_id UUID PRIMARY KEY REFERENCES entity_list_removal_reviews(id),
    validated_by UUID NOT NULL,
    project_id UUID NOT NULL,
    form_id UUID NOT NULL,
    filename VARCHAR(255) NOT NULL,
    expected_revision BIGINT NOT NULL,
    expected_sha256 VARCHAR(64) NOT NULL,
    candidate_sha256 VARCHAR(64) NOT NULL,
    validated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (project_id, form_id, filename, expected_revision, expected_sha256)
        REFERENCES entity_list_snapshot_history(project_id, form_id, filename, revision, sha256)
);
-- split
CREATE TRIGGER entity_list_validation_seals_immutable
BEFORE UPDATE OR DELETE ON entity_list_removal_validation_seals
FOR EACH ROW EXECUTE FUNCTION deny_entity_list_history_mutation();

-- split
-- Immutable denial evidence is written on an independent transaction.
-- Disposable candidate schema only; NOT a production migration.
CREATE TABLE entity_list_denial_evidence (
    id UUID PRIMARY KEY,
    -- Nullable for malformed requests without a trusted review identifier.
    -- The server-generated event ID remains the correlation key.
    review_id UUID,
    actor_id UUID,
    operation VARCHAR(32) NOT NULL
        CHECK (operation IN ('REVIEW_DECISION','APPROVAL_VALIDATION')),
    reason VARCHAR(32) NOT NULL
        CHECK (reason IN ('UNAUTHENTICATED','INACTIVE_ACCOUNT','SELF_ACTION',
                         'INVALID_STATE','PERMISSION_DENIED','STALE_HEAD',
                         'EVIDENCE_MISMATCH','REPLAY','UNAVAILABLE')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- split
CREATE TRIGGER entity_list_denial_evidence_immutable
BEFORE UPDATE OR DELETE ON entity_list_denial_evidence
FOR EACH ROW EXECUTE FUNCTION deny_entity_list_history_mutation();

-- split
-- Disposable explicit removal role delegation (NOT project manager roles).
-- No application provisioning, controller or production migration.
CREATE TABLE entity_list_removal_role_grants (
    project_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role_name VARCHAR(16) NOT NULL CHECK (role_name IN ('REVIEWER','EXECUTOR')),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (project_id, user_id, role_name)
);

-- split
-- Test-only provisioned grant-administrator registry. Not self-service.
CREATE TABLE entity_list_removal_grant_administrators (
    project_id UUID NOT NULL,
    administrator_id UUID NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (project_id, administrator_id)
);
-- split
-- Immutable transition history: changes to grant state and audit occur atomically.
CREATE TABLE entity_list_removal_grant_audit (
    event_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    administrator_id UUID NOT NULL,
    subject_id UUID NOT NULL,
    role_name VARCHAR(16) NOT NULL CHECK (role_name IN ('REVIEWER','EXECUTOR')),
    action VARCHAR(8) NOT NULL CHECK (action IN ('GRANT','REVOKE')),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (administrator_id <> subject_id)
);
-- split
CREATE TRIGGER entity_list_grant_audit_immutable
BEFORE UPDATE OR DELETE ON entity_list_removal_grant_audit
FOR EACH ROW EXECUTE FUNCTION deny_entity_list_history_mutation();

-- split
-- Provisioned out of band: independent test-only authority for managing
-- grant administrators. No production bootstrap or administrator endpoint.
CREATE TABLE entity_list_grant_custodians (
    project_id UUID NOT NULL,
    custodian_id UUID NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (project_id, custodian_id)
);
-- split
CREATE TABLE entity_list_grant_administrator_audit (
    event_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    custodian_id UUID NOT NULL,
    administrator_id UUID NOT NULL,
    action VARCHAR(8) NOT NULL CHECK (action IN ('APPOINT','REVOKE')),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (custodian_id <> administrator_id)
);
-- split
CREATE TRIGGER entity_list_grant_administrator_audit_immutable
BEFORE UPDATE OR DELETE ON entity_list_grant_administrator_audit
FOR EACH ROW EXECUTE FUNCTION deny_entity_list_history_mutation();

-- split
-- Test-only custody approval intent; intentionally has NO trigger that
-- changes custody. A quorum is evidence, not authority to bootstrap.
CREATE TABLE entity_list_custodian_change_proposals (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    form_id UUID NOT NULL,
    filename VARCHAR(255) NOT NULL,
    subject_id UUID NOT NULL,
    initiated_by UUID NOT NULL,
    action VARCHAR(8) NOT NULL CHECK (action IN ('APPOINT','REVOKE')),
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING','ENDORSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (subject_id <> initiated_by)
);
-- split
-- Independent witnesses are immutable; their agreement is never a
-- custodian or role mutation, and does not create a real-world identity.
CREATE TABLE entity_list_custodian_change_witnesses (
    proposal_id UUID NOT NULL REFERENCES entity_list_custodian_change_proposals(id),
    witness_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (proposal_id,witness_id)
);
-- split
CREATE TRIGGER entity_list_custodian_witness_immutable
BEFORE UPDATE OR DELETE ON entity_list_custodian_change_witnesses
FOR EACH ROW EXECUTE FUNCTION deny_entity_list_history_mutation();

-- split
-- Proposal evidence cannot be edited; only the one-way quorum state
-- transition PENDING -> ENDORSED is permitted.
CREATE FUNCTION prevent_entity_list_custodian_proposal_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.id IS DISTINCT FROM NEW.id
       OR OLD.project_id IS DISTINCT FROM NEW.project_id
       OR OLD.form_id IS DISTINCT FROM NEW.form_id
       OR OLD.filename IS DISTINCT FROM NEW.filename
       OR OLD.subject_id IS DISTINCT FROM NEW.subject_id
       OR OLD.initiated_by IS DISTINCT FROM NEW.initiated_by
       OR OLD.action IS DISTINCT FROM NEW.action
       OR OLD.created_at IS DISTINCT FROM NEW.created_at
       OR OLD.state <> 'PENDING'
       OR NEW.state <> 'ENDORSED' THEN
        RAISE EXCEPTION 'Custodian proposal evidence is immutable';
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;
-- split
CREATE TRIGGER entity_list_custodian_proposal_immutable
BEFORE UPDATE ON entity_list_custodian_change_proposals
FOR EACH ROW EXECUTE FUNCTION prevent_entity_list_custodian_proposal_mutation();
-- split
CREATE TRIGGER entity_list_custodian_proposal_no_delete
BEFORE DELETE ON entity_list_custodian_change_proposals
FOR EACH ROW EXECUTE FUNCTION deny_entity_list_history_mutation();
