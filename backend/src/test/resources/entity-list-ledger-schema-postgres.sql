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
