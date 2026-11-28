-- U06 Package 3: non-authorizing explicit mapping provenance.
-- No backfill, no issuer, no operational grants.
CREATE TABLE typed_mapping_explicit_proposals (
    recommendation_id UUID PRIMARY KEY REFERENCES typed_mapping_review_recommendations(id),
    manifest_sha256 VARCHAR(64) NOT NULL,
    manifest_canonical VARCHAR(18000) NOT NULL,
    recommendation_sha256 VARCHAR(64) NOT NULL,
    recorded_by UUID NOT NULL REFERENCES users(id),
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE typed_mapping_explicit_endorsements (
    recommendation_id UUID PRIMARY KEY REFERENCES typed_mapping_explicit_proposals(recommendation_id),
    manifest_sha256 VARCHAR(64) NOT NULL,
    recommendation_sha256 VARCHAR(64) NOT NULL,
    reviewer_id UUID NOT NULL REFERENCES users(id),
    signer_id UUID NOT NULL REFERENCES users(id),
    evidence_sha256 VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (reviewer_id <> signer_id)
);
