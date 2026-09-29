CREATE TABLE notification_intent_receipt (
    event_id UUID PRIMARY KEY,
    notification_id UUID NOT NULL UNIQUE
        REFERENCES notification_inbox(notification_id) ON DELETE RESTRICT,
    payload_sha256 CHAR(64) NOT NULL,
    topic VARCHAR(249) NOT NULL,
    source_partition INTEGER NOT NULL,
    source_offset BIGINT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT notification_intent_receipt_payload_sha256_valid
        CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT notification_intent_receipt_topic_not_blank CHECK (btrim(topic) <> ''),
    CONSTRAINT notification_intent_receipt_partition_valid CHECK (source_partition >= 0),
    CONSTRAINT notification_intent_receipt_offset_valid CHECK (source_offset >= 0),
    CONSTRAINT notification_intent_receipt_timestamps_valid CHECK (received_at <= processed_at)
);

CREATE INDEX notification_intent_receipt_processed_idx
    ON notification_intent_receipt (processed_at, event_id);
