ALTER TABLE catalog_learning_resource
    ADD COLUMN license_expression VARCHAR(64),
    ADD COLUMN license_url VARCHAR(2048),
    ADD COLUMN access_mode VARCHAR(24),
    ADD COLUMN read_url VARCHAR(2048);

UPDATE catalog_learning_resource
SET is_active = FALSE
WHERE license_expression IS NULL OR access_mode IS NULL;

ALTER TABLE catalog_learning_resource
    ADD CONSTRAINT catalog_learning_resource_access_metadata_valid CHECK (
        (NOT is_active) OR (
            license_expression IN ('CC-BY', 'CC-BY-SA', 'CC0', 'PUBLIC-DOMAIN') AND
            license_url LIKE 'https://%' AND
            access_mode IN ('DOWNLOAD', 'READ_AT_SOURCE') AND
            (
                (access_mode = 'DOWNLOAD' AND read_url IS NULL) OR
                (access_mode = 'READ_AT_SOURCE' AND read_url LIKE 'https://%')
            )
        )
    );

COMMENT ON COLUMN catalog_learning_resource.license_expression IS
    'Display metadata copied from a verified import. Digital Content remains authoritative for download authorization.';
