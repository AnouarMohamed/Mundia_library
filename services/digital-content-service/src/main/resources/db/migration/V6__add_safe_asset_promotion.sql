CREATE TABLE digital_content_promotion_job (
    ingestion_id UUID PRIMARY KEY REFERENCES digital_content_ingestion(ingestion_id),
    asset_id UUID NOT NULL UNIQUE,
    destination_object_key VARCHAR(512) NOT NULL UNIQUE,
    state VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    promoted_object_version_id VARCHAR(1024),
    promoted_object_etag VARCHAR(128),
    promoted_checksum_sha256 CHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT digital_content_promotion_key_check CHECK (
        destination_object_key ~ '^digital-content/[0-9a-f]{2}/[0-9a-f-]{36}/[0-9a-f]{64}[.](pdf|epub)$'
    ),
    CONSTRAINT digital_content_promotion_state_check CHECK (
        state IN ('PENDING', 'LEASED', 'RETRY', 'COMPLETED', 'BLOCKED', 'CANCELLED')
    ),
    CONSTRAINT digital_content_promotion_attempt_check CHECK (attempt_count >= 0),
    CONSTRAINT digital_content_promotion_lease_check CHECK (
        (state = 'LEASED' AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL) OR
        (state <> 'LEASED' AND lease_token IS NULL AND lease_expires_at IS NULL)
    ),
    CONSTRAINT digital_content_promotion_error_check CHECK (
        last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{1,63}$'
    ),
    CONSTRAINT digital_content_promotion_checksum_check CHECK (
        promoted_checksum_sha256 IS NULL OR promoted_checksum_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT digital_content_promotion_completion_check CHECK (
        (state = 'COMPLETED' AND completed_at IS NOT NULL AND promoted_object_version_id IS NOT NULL
            AND promoted_object_etag IS NOT NULL AND promoted_checksum_sha256 IS NOT NULL) OR
        (state <> 'COMPLETED' AND completed_at IS NULL)
    ),
    CONSTRAINT digital_content_promotion_timestamps_check CHECK (updated_at >= created_at)
);

CREATE INDEX digital_content_promotion_claim_idx
    ON digital_content_promotion_job (next_attempt_at, ingestion_id)
    WHERE state IN ('PENDING', 'RETRY', 'LEASED');

COMMENT ON TABLE digital_content_promotion_job IS
    'Durable, lease-fenced quarantine-to-private-publication work. Object copying happens outside the database transaction.';
COMMENT ON COLUMN digital_content_promotion_job.destination_object_key IS
    'Private immutable delivery key. Never expose it through browser-facing APIs.';
