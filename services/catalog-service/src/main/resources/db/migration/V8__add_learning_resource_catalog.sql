CREATE TABLE catalog_learning_resource (
    resource_id UUID PRIMARY KEY,
    source_name VARCHAR(100) NOT NULL,
    source_record_key VARCHAR(512) NOT NULL,
    title VARCHAR(500) NOT NULL,
    author VARCHAR(500),
    description VARCHAR(4000),
    category VARCHAR(128) NOT NULL,
    language VARCHAR(16) NOT NULL,
    cover_url VARCHAR(2048),
    cover_alt VARCHAR(300),
    source_url VARCHAR(2048) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    source_revision VARCHAR(128) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT catalog_learning_resource_source_key_unique UNIQUE (source_name, source_record_key),
    CONSTRAINT catalog_learning_resource_content_hash_valid CHECK (char_length(content_sha256) = 64),
    CONSTRAINT catalog_learning_resource_timestamps_valid CHECK (updated_at >= created_at),
    CONSTRAINT catalog_learning_resource_cover_valid CHECK (
        (cover_url IS NULL AND cover_alt IS NULL) OR
        (cover_url LIKE 'https://%' AND cover_alt IS NOT NULL)
    )
);

CREATE INDEX catalog_learning_resource_browse_idx
    ON catalog_learning_resource (is_active, category, title, resource_id);
-- [jooq ignore start]
CREATE INDEX catalog_learning_resource_search_idx
    ON catalog_learning_resource USING GIN (
        to_tsvector('simple',
            coalesce(title, '') || ' ' || coalesce(author, '') || ' ' ||
            coalesce(category, '') || ' ' || coalesce(description, ''))
    );
-- [jooq ignore stop]

CREATE TABLE catalog_learning_resource_import (
    import_id UUID PRIMARY KEY,
    source_name VARCHAR(100) NOT NULL,
    source_revision VARCHAR(128) NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL,
    actor_fingerprint CHAR(64) NOT NULL,
    record_count INTEGER NOT NULL,
    inserted_count INTEGER NOT NULL,
    updated_count INTEGER NOT NULL,
    unchanged_count INTEGER NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT catalog_learning_resource_import_hashes_valid CHECK (
        char_length(manifest_sha256) = 64 AND char_length(actor_fingerprint) = 64
    ),
    CONSTRAINT catalog_learning_resource_import_counts_valid CHECK (
        record_count BETWEEN 1 AND 250 AND inserted_count >= 0 AND
        updated_count >= 0 AND unchanged_count >= 0 AND
        inserted_count + updated_count + unchanged_count = record_count
    )
);

CREATE INDEX catalog_learning_resource_import_source_idx
    ON catalog_learning_resource_import (source_name, completed_at DESC, import_id);
