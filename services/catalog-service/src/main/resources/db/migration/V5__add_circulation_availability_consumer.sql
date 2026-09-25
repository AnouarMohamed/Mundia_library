-- The edition-level cache is disposable. Clear legacy/backfill values before
-- rebuilding it exclusively from authoritative per-copy Circulation events.
TRUNCATE TABLE catalog_edition_availability_projection;

CREATE TABLE catalog_copy_availability_projection (
    copy_id UUID PRIMARY KEY,
    edition_id UUID NOT NULL REFERENCES catalog_edition(edition_id) ON DELETE RESTRICT,
    status VARCHAR(32) NOT NULL,
    source_version BIGINT NOT NULL,
    source_event_id UUID NOT NULL UNIQUE,
    source_occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT catalog_copy_availability_status_valid
        CHECK (status IN ('AVAILABLE', 'ON_LOAN', 'RESERVED', 'LOST', 'DAMAGED', 'WITHDRAWN')),
    CONSTRAINT catalog_copy_availability_source_version_valid CHECK (source_version >= 0),
    CONSTRAINT catalog_copy_availability_timestamps_valid CHECK (created_at <= updated_at)
);

CREATE INDEX catalog_copy_availability_edition_index
    ON catalog_copy_availability_projection (edition_id, status, copy_id);

CREATE TABLE catalog_consumer_inbox (
    consumer_name VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(160) NOT NULL,
    event_version INTEGER NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    disposition VARCHAR(32) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT catalog_consumer_inbox_aggregate_version_unique
        UNIQUE (consumer_name, aggregate_type, aggregate_id, aggregate_version),
    CONSTRAINT catalog_consumer_inbox_event_version_valid CHECK (event_version > 0),
    CONSTRAINT catalog_consumer_inbox_aggregate_version_valid CHECK (aggregate_version >= 0),
    CONSTRAINT catalog_consumer_inbox_payload_sha256_valid
        CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT catalog_consumer_inbox_disposition_valid CHECK (disposition IN ('APPLIED', 'STALE')),
    CONSTRAINT catalog_consumer_inbox_timestamps_valid CHECK (received_at <= processed_at)
);

CREATE INDEX catalog_consumer_inbox_processed_index
    ON catalog_consumer_inbox (processed_at);
