CREATE TABLE digital_content_external_resource (
    resource_id UUID PRIMARY KEY,
    source_provider VARCHAR(100) NOT NULL,
    source_uri VARCHAR(2048) NOT NULL,
    download_uri VARCHAR(2048) NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    license_expression VARCHAR(32) NOT NULL,
    license_uri VARCHAR(2048) NOT NULL,
    attribution VARCHAR(1000) NOT NULL,
    rights_status VARCHAR(16) NOT NULL,
    rights_verified_at TIMESTAMPTZ NOT NULL,
    rights_expires_at TIMESTAMPTZ,
    territory_scope VARCHAR(16) NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL,
    registered_by CHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT digital_content_external_source_provider_check CHECK (
        length(trim(source_provider)) BETWEEN 2 AND 100
    ),
    CONSTRAINT digital_content_external_source_uri_check CHECK (source_uri ~ '^https://'),
    CONSTRAINT digital_content_external_download_uri_check CHECK (download_uri ~ '^https://'),
    CONSTRAINT digital_content_external_media_type_check CHECK (
        media_type IN ('application/pdf', 'application/epub+zip')
    ),
    CONSTRAINT digital_content_external_license_check CHECK (
        license_expression IN ('CC-BY-4.0', 'CC-BY-SA-4.0', 'CC0-1.0', 'PDM-1.0')
    ),
    CONSTRAINT digital_content_external_license_uri_check CHECK (license_uri ~ '^https://'),
    CONSTRAINT digital_content_external_attribution_check CHECK (
        length(trim(attribution)) BETWEEN 2 AND 1000
    ),
    CONSTRAINT digital_content_external_rights_status_check CHECK (
        rights_status IN ('VERIFIED', 'REVOKED', 'EXPIRED')
    ),
    CONSTRAINT digital_content_external_rights_expiry_check CHECK (
        rights_expires_at IS NULL OR rights_expires_at > rights_verified_at
    ),
    CONSTRAINT digital_content_external_territory_check CHECK (
        territory_scope IN ('GLOBAL', 'RESTRICTED')
    ),
    CONSTRAINT digital_content_external_manifest_check CHECK (
        manifest_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT digital_content_external_actor_check CHECK (
        registered_by ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT digital_content_external_version_check CHECK (version >= 0),
    CONSTRAINT digital_content_external_timestamps_check CHECK (updated_at >= created_at)
);

CREATE INDEX digital_content_external_available_idx
    ON digital_content_external_resource (resource_id)
    WHERE rights_status = 'VERIFIED' AND territory_scope = 'GLOBAL';

CREATE TABLE digital_content_external_authorization_audit (
    authorization_id UUID PRIMARY KEY,
    resource_id UUID NOT NULL REFERENCES digital_content_external_resource(resource_id),
    actor_fingerprint CHAR(64) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT digital_content_external_authorization_actor_check CHECK (
        actor_fingerprint ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX digital_content_external_authorization_resource_idx
    ON digital_content_external_authorization_audit (resource_id, issued_at DESC);

COMMENT ON TABLE digital_content_external_resource IS
    'Rights-reviewed external files. Bytes remain at the official source and are never proxied by this service.';
COMMENT ON COLUMN digital_content_external_resource.download_uri IS
    'Returned only after caller-bound authorization; registration rejects unsafe URI forms.';
COMMENT ON TABLE digital_content_external_authorization_audit IS
    'Privacy-minimized record of external download authorizations; member identifiers are never stored.';
