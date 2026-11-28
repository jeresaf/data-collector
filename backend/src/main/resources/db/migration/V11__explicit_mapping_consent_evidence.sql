-- Non-authorizing consent-evidence records. No consent or grants are backfilled.
CREATE TABLE typed_mapping_consent_evidence (
    recommendation_id UUID PRIMARY KEY REFERENCES typed_mapping_explicit_endorsements(recommendation_id),
    endorsement_sha256 VARCHAR(64) NOT NULL,
    manifest_sha256 VARCHAR(64) NOT NULL,
    external_evidence_sha256 VARCHAR(64) NOT NULL,
    attestor_id UUID NOT NULL REFERENCES users(id),
    expires_epoch_second BIGINT NOT NULL,
    evidence_sha256 VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE typed_mapping_consent_withdrawals (
    recommendation_id UUID PRIMARY KEY REFERENCES typed_mapping_consent_evidence(recommendation_id),
    consent_evidence_sha256 VARCHAR(64) NOT NULL,
    recorded_by UUID NOT NULL REFERENCES users(id),
    reason VARCHAR(32) NOT NULL CHECK (reason IN ('CONSENT_WITHDRAWN','EVIDENCE_INVALID','SECURITY')),
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
