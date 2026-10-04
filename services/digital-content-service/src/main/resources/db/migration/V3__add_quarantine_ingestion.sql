CREATE TABLE digital_content_ingestion (
    ingestion_id UUID PRIMARY KEY,
    request_digest CHAR(64) NOT NULL,
    edition_id UUID NOT NULL,
    format VARCHAR(16) NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    quarantine_object_key VARCHAR(512) NOT NULL UNIQUE,
    source_provider VARCHAR(100) NOT NULL,
    source_uri VARCHAR(2048) NOT NULL,
    license_expression VARCHAR(128) NOT NULL,
    attribution VARCHAR(1000) NOT NULL,
    rights_verified_at TIMESTAMPTZ NOT NULL,
    rights_expires_at TIMESTAMPTZ,
    state VARCHAR(24) NOT NULL,
    actor_fingerprint CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    object_version_id VARCHAR(1024),
    object_etag VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT digital_content_ingestion_request_digest_check CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT digital_content_ingestion_format_check CHECK (format IN ('PDF', 'EPUB')),
    CONSTRAINT digital_content_ingestion_media_type_check CHECK (
        (format = 'PDF' AND media_type = 'application/pdf') OR
        (format = 'EPUB' AND media_type = 'application/epub+zip')
    ),
    CONSTRAINT digital_content_ingestion_size_check CHECK (size_bytes BETWEEN 1 AND 1073741824),
    CONSTRAINT digital_content_ingestion_sha256_check CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT digital_content_ingestion_object_key_check CHECK (
        quarantine_object_key ~ '^quarantine/digital-content/[0-9a-f]{2}/[0-9a-f-]{36}/[0-9a-f]{64}[.](pdf|epub)$'
    ),
    CONSTRAINT digital_content_ingestion_source_provider_check CHECK (length(trim(source_provider)) BETWEEN 2 AND 100),
    CONSTRAINT digital_content_ingestion_source_uri_check CHECK (source_uri ~ '^https://'),
    CONSTRAINT digital_content_ingestion_license_check CHECK (
        license_expression IN ('CC-BY-4.0', 'CC-BY-SA-4.0', 'CC0-1.0', 'PDM-1.0')
    ),
    CONSTRAINT digital_content_ingestion_attribution_check CHECK (length(trim(attribution)) BETWEEN 2 AND 1000),
    CONSTRAINT digital_content_ingestion_rights_expiry_check CHECK (
        rights_expires_at IS NULL OR rights_expires_at > rights_verified_at
    ),
    CONSTRAINT digital_content_ingestion_state_check CHECK (
        state IN ('AWAITING_SCAN', 'CLEAN', 'REJECTED', 'PROMOTED', 'EXPIRED')
    ),
    CONSTRAINT digital_content_ingestion_actor_check CHECK (actor_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT digital_content_ingestion_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT digital_content_ingestion_timestamps_check CHECK (updated_at >= created_at)
);

CREATE INDEX digital_content_ingestion_state_expiry_idx
    ON digital_content_ingestion (state, expires_at, ingestion_id);

COMMENT ON TABLE digital_content_ingestion IS
    'Rights-reviewed manifests for immutable objects uploaded only to private quarantine storage.';
COMMENT ON COLUMN digital_content_ingestion.quarantine_object_key IS
    'Opaque private key. Never expose except inside a short-lived, checksum-bound upload grant.';
COMMENT ON COLUMN digital_content_ingestion.actor_fingerprint IS
    'SHA-256 fingerprint of issuer and subject; raw operator identity is intentionally not stored.';
