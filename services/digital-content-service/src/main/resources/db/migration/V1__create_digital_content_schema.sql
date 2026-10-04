CREATE TABLE digital_content_asset (
    asset_id UUID PRIMARY KEY,
    edition_id UUID NOT NULL,
    format VARCHAR(16) NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL UNIQUE,
    source_provider VARCHAR(100) NOT NULL,
    source_uri VARCHAR(2048) NOT NULL,
    license_expression VARCHAR(128) NOT NULL,
    attribution VARCHAR(1000) NOT NULL,
    rights_status VARCHAR(16) NOT NULL,
    rights_verified_at TIMESTAMPTZ NOT NULL,
    rights_expires_at TIMESTAMPTZ,
    territory_scope VARCHAR(16) NOT NULL,
    malware_scan_status VARCHAR(16) NOT NULL,
    publication_status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT digital_content_asset_edition_format_key UNIQUE (edition_id, format),
    CONSTRAINT digital_content_asset_format_check CHECK (format IN ('PDF', 'EPUB')),
    CONSTRAINT digital_content_asset_media_type_check CHECK (
        (format = 'PDF' AND media_type = 'application/pdf') OR
        (format = 'EPUB' AND media_type = 'application/epub+zip')
    ),
    CONSTRAINT digital_content_asset_size_check CHECK (size_bytes BETWEEN 1 AND 1073741824),
    CONSTRAINT digital_content_asset_sha256_check CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT digital_content_asset_object_key_check CHECK (
        object_key ~ '^digital-content/[0-9a-f]{2}/[0-9a-f-]{36}/[0-9a-f]{64}[.](pdf|epub)$'
    ),
    CONSTRAINT digital_content_asset_source_provider_check CHECK (length(trim(source_provider)) BETWEEN 2 AND 100),
    CONSTRAINT digital_content_asset_source_uri_check CHECK (source_uri ~ '^https://'),
    CONSTRAINT digital_content_asset_license_check CHECK (length(trim(license_expression)) BETWEEN 2 AND 128),
    CONSTRAINT digital_content_asset_attribution_check CHECK (length(trim(attribution)) BETWEEN 2 AND 1000),
    CONSTRAINT digital_content_asset_rights_status_check CHECK (rights_status IN ('PENDING', 'VERIFIED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT digital_content_asset_rights_expiry_check CHECK (
        rights_expires_at IS NULL OR rights_expires_at > rights_verified_at
    ),
    CONSTRAINT digital_content_asset_territory_check CHECK (territory_scope IN ('GLOBAL', 'RESTRICTED')),
    CONSTRAINT digital_content_asset_scan_check CHECK (malware_scan_status IN ('PENDING', 'CLEAN', 'INFECTED', 'ERROR')),
    CONSTRAINT digital_content_asset_publication_check CHECK (publication_status IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN')),
    CONSTRAINT digital_content_asset_version_check CHECK (version >= 0),
    CONSTRAINT digital_content_asset_timestamps_check CHECK (updated_at >= created_at)
);

CREATE INDEX digital_content_asset_available_edition_idx
    ON digital_content_asset (edition_id, format, asset_id)
    WHERE rights_status = 'VERIFIED'
      AND territory_scope = 'GLOBAL'
      AND malware_scan_status = 'CLEAN'
      AND publication_status = 'PUBLISHED';

COMMENT ON COLUMN digital_content_asset.object_key IS
    'Private object-store key; never return this field through a browser-facing API.';
COMMENT ON COLUMN digital_content_asset.source_uri IS
    'Rights/audit provenance only; never use as an unvalidated download redirect.';
