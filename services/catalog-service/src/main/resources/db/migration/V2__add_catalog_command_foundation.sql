ALTER TABLE catalog_work
    ADD COLUMN aggregate_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT catalog_work_version_valid CHECK (aggregate_version >= 0);

ALTER TABLE catalog_edition
    ADD COLUMN aggregate_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT catalog_edition_version_valid CHECK (aggregate_version >= 0);

CREATE TABLE catalog_command_idempotency (
    owner_fingerprint CHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    response_status INTEGER,
    aggregate_type VARCHAR(32),
    aggregate_id UUID,
    aggregate_version BIGINT,
    occurred_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (owner_fingerprint, idempotency_key),
    CONSTRAINT catalog_command_owner_fingerprint_valid
        CHECK (owner_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT catalog_command_request_fingerprint_valid
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT catalog_command_idempotency_key_valid
        CHECK (char_length(idempotency_key) BETWEEN 16 AND 128),
    CONSTRAINT catalog_command_operation_valid
        CHECK (operation IN ('CREATE_WORK', 'CREATE_EDITION')),
    CONSTRAINT catalog_command_response_status_valid
        CHECK (response_status IS NULL OR response_status = 201),
    CONSTRAINT catalog_command_aggregate_type_valid
        CHECK (aggregate_type IS NULL OR aggregate_type IN ('work', 'edition')),
    CONSTRAINT catalog_command_version_valid
        CHECK (aggregate_version IS NULL OR aggregate_version >= 0),
    CONSTRAINT catalog_command_completion_valid CHECK (
        (completed_at IS NULL AND response_status IS NULL AND aggregate_type IS NULL
            AND aggregate_id IS NULL AND aggregate_version IS NULL AND occurred_at IS NULL)
        OR
        (completed_at IS NOT NULL AND response_status IS NOT NULL AND aggregate_type IS NOT NULL
            AND aggregate_id IS NOT NULL AND aggregate_version IS NOT NULL AND occurred_at IS NOT NULL)
    ),
    CONSTRAINT catalog_command_time_order_valid CHECK (
        expires_at > created_at AND (completed_at IS NULL OR completed_at >= created_at)
    )
);

CREATE INDEX catalog_command_idempotency_expiry_index
    ON catalog_command_idempotency (expires_at);

CREATE TABLE catalog_audit_entry (
    audit_id UUID PRIMARY KEY,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    operation VARCHAR(64) NOT NULL,
    actor_fingerprint CHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    previous_state JSONB,
    resulting_state JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT catalog_audit_aggregate_type_valid
        CHECK (aggregate_type IN ('work', 'edition')),
    CONSTRAINT catalog_audit_version_valid CHECK (aggregate_version >= 0),
    CONSTRAINT catalog_audit_operation_valid
        CHECK (operation IN ('CREATE_WORK', 'CREATE_EDITION')),
    CONSTRAINT catalog_audit_actor_fingerprint_valid
        CHECK (actor_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT catalog_audit_reason_valid
        CHECK (reason = btrim(reason) AND char_length(reason) BETWEEN 8 AND 500)
);

CREATE UNIQUE INDEX catalog_audit_aggregate_version_index
    ON catalog_audit_entry (aggregate_type, aggregate_id, aggregate_version);

CREATE TABLE catalog_outbox_event (
    event_id UUID PRIMARY KEY,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    event_version INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    CONSTRAINT catalog_outbox_aggregate_type_valid
        CHECK (aggregate_type IN ('work', 'edition')),
    CONSTRAINT catalog_outbox_aggregate_version_valid CHECK (aggregate_version >= 0),
    CONSTRAINT catalog_outbox_event_type_valid
        CHECK (event_type IN ('catalog.work.created', 'catalog.edition.created')),
    CONSTRAINT catalog_outbox_event_version_valid CHECK (event_version = 1),
    CONSTRAINT catalog_outbox_time_order_valid
        CHECK (published_at IS NULL OR published_at >= occurred_at)
);

CREATE UNIQUE INDEX catalog_outbox_aggregate_version_index
    ON catalog_outbox_event (aggregate_type, aggregate_id, aggregate_version);
CREATE INDEX catalog_outbox_unpublished_index
    ON catalog_outbox_event (created_at, event_id)
    WHERE published_at IS NULL;
