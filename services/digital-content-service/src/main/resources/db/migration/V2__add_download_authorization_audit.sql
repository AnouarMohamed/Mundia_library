CREATE TABLE digital_content_download_authorization_audit (
    authorization_id UUID PRIMARY KEY,
    asset_id UUID NOT NULL REFERENCES digital_content_asset(asset_id),
    actor_fingerprint CHAR(64) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT digital_content_download_audit_actor_check CHECK (
        actor_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT digital_content_download_audit_expiry_check CHECK (
        expires_at > issued_at AND
        EXTRACT(EPOCH FROM (expires_at - issued_at)) <= 300
    )
);

CREATE INDEX digital_content_download_audit_asset_issued_idx
    ON digital_content_download_authorization_audit (asset_id, issued_at DESC);

CREATE INDEX digital_content_download_audit_issued_idx
    ON digital_content_download_authorization_audit (issued_at);

COMMENT ON TABLE digital_content_download_authorization_audit IS
    'Privacy-minimized record of issued download grants; signed URLs and member identifiers are never persisted.';
