-- M3D4C2B2B1: OFFLINE ceremony EVIDENCE COMMITMENTS only.
-- Independent third web Project Admin witnesses an external bundle HASH.
-- This does not verify an offline ceremony, activate the proposed public key,
-- issue a signed grant, or authorize historical answer copying.
-- Production Flyway V1-V6 remain immutable.
CREATE TABLE typed_mapping_key_ceremony_commitments (
    request_id UUID PRIMARY KEY
        REFERENCES typed_mapping_key_enrollment_requests(id),
    project_id UUID NOT NULL REFERENCES projects(id),
    proposal_reviewer_user_id UUID NOT NULL REFERENCES users(id),
    witness_user_id UUID NOT NULL REFERENCES users(id),
    client_request_id VARCHAR(128) NOT NULL,
    proposal_evidence_sha256 VARCHAR(64) NOT NULL,
    reviewer_evidence_sha256 VARCHAR(64) NOT NULL,
    observed_key_sha256 VARCHAR(64) NOT NULL,
    external_bundle_sha256 VARCHAR(64) NOT NULL,
    external_reference VARCHAR(128) NOT NULL,
    commitment_sha256 VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ceremony_witness_not_reviewer
        CHECK (proposal_reviewer_user_id <> witness_user_id),
    CONSTRAINT uq_ceremony_witness_client_intent
        UNIQUE (project_id, witness_user_id, client_request_id)
);
CREATE INDEX idx_ceremony_commitments_project
    ON typed_mapping_key_ceremony_commitments(project_id, recorded_at);
-- Not a digitally signed attestation or DBA-immutable audit anchor.
-- The external evidence bundle is not uploaded, independently verified or
-- signed by a hardware trust root in this slice. Never populate V5 trust keys.
