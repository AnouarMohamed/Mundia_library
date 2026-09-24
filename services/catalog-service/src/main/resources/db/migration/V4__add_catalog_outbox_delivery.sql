ALTER TABLE catalog_outbox_event
    ADD COLUMN delivery_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN last_attempt_at TIMESTAMPTZ,
    ADD COLUMN lease_owner VARCHAR(100),
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN broker_topic VARCHAR(249),
    ADD COLUMN broker_partition INTEGER,
    ADD COLUMN broker_offset BIGINT,
    ADD COLUMN last_error_code VARCHAR(64),
    ADD COLUMN blocked_at TIMESTAMPTZ;

ALTER TABLE catalog_outbox_event
    ADD CONSTRAINT catalog_outbox_delivery_attempts_valid CHECK (
        (delivery_attempts = 0 AND last_attempt_at IS NULL)
        OR (delivery_attempts > 0 AND last_attempt_at IS NOT NULL)
    ),
    ADD CONSTRAINT catalog_outbox_delivery_lease_valid CHECK (
        (lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
        OR (
            lease_owner IS NOT NULL
            AND lease_token IS NOT NULL
            AND lease_expires_at IS NOT NULL
            AND lease_owner ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$'
        )
    ),
    ADD CONSTRAINT catalog_outbox_delivery_publication_valid CHECK (
        (
            published_at IS NULL
            AND broker_topic IS NULL
            AND broker_partition IS NULL
            AND broker_offset IS NULL
        )
        OR (
            published_at IS NOT NULL
            AND broker_topic ~ '^[A-Za-z0-9._-]{1,249}$'
            AND broker_partition >= 0
            AND broker_offset >= 0
            AND lease_owner IS NULL
            AND lease_token IS NULL
            AND lease_expires_at IS NULL
            AND blocked_at IS NULL
        )
    ),
    ADD CONSTRAINT catalog_outbox_delivery_blocked_valid CHECK (
        blocked_at IS NULL
        OR (
            published_at IS NULL
            AND lease_owner IS NULL
            AND lease_token IS NULL
            AND lease_expires_at IS NULL
        )
    ),
    ADD CONSTRAINT catalog_outbox_delivery_error_valid CHECK (
        last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
    );

CREATE INDEX catalog_outbox_delivery_ready_index
    ON catalog_outbox_event (next_attempt_at, created_at, event_id)
    WHERE published_at IS NULL AND blocked_at IS NULL;

CREATE INDEX catalog_outbox_delivery_lease_index
    ON catalog_outbox_event (lease_expires_at)
    WHERE published_at IS NULL AND blocked_at IS NULL AND lease_expires_at IS NOT NULL;

CREATE INDEX catalog_outbox_delivery_blocked_index
    ON catalog_outbox_event (blocked_at)
    WHERE blocked_at IS NOT NULL;

CREATE INDEX catalog_outbox_published_retention_index
    ON catalog_outbox_event (published_at, event_id)
    WHERE published_at IS NOT NULL;
