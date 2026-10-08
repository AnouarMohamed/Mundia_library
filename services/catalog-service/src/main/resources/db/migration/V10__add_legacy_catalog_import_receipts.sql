CREATE TABLE catalog_legacy_import (
    import_id UUID PRIMARY KEY,
    source_revision CHAR(64) NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL,
    actor_fingerprint CHAR(64) NOT NULL,
    work_count INTEGER NOT NULL,
    edition_count INTEGER NOT NULL,
    contributor_count INTEGER NOT NULL,
    review_count INTEGER NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT catalog_legacy_import_hashes_valid CHECK (
        source_revision ~ '^[0-9a-f]{64}$' AND
        manifest_sha256 ~ '^[0-9a-f]{64}$' AND
        actor_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT catalog_legacy_import_counts_valid CHECK (
        work_count BETWEEN 1 AND 10 AND
        edition_count = work_count AND
        contributor_count BETWEEN 1 AND work_count AND
        review_count BETWEEN 0 AND 1000
    )
);

CREATE INDEX catalog_legacy_import_completed_index
    ON catalog_legacy_import (completed_at DESC, import_id);
