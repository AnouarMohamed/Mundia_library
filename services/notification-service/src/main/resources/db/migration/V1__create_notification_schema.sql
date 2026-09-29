CREATE TABLE notification_preference (
    member_id UUID PRIMARY KEY,
    in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    email_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    due_soon_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    overdue_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    hold_ready_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    account_status_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT notification_preference_version_nonnegative CHECK (version >= 0)
);

CREATE TABLE notification_inbox (
    notification_id UUID PRIMARY KEY,
    member_id UUID NOT NULL,
    source_event_id UUID NOT NULL UNIQUE,
    source_type VARCHAR(100) NOT NULL,
    category VARCHAR(32) NOT NULL,
    subject VARCHAR(160) NOT NULL,
    body VARCHAR(2000) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    CONSTRAINT notification_inbox_source_type_not_blank CHECK (btrim(source_type) <> ''),
    CONSTRAINT notification_inbox_category_valid CHECK (category IN ('DUE_SOON', 'OVERDUE', 'HOLD_READY', 'ACCOUNT_STATUS', 'GENERAL')),
    CONSTRAINT notification_inbox_subject_not_blank CHECK (btrim(subject) <> ''),
    CONSTRAINT notification_inbox_body_not_blank CHECK (btrim(body) <> ''),
    CONSTRAINT notification_inbox_timestamp_order CHECK (created_at >= occurred_at),
    CONSTRAINT notification_inbox_read_timestamp_order CHECK (read_at IS NULL OR read_at >= created_at)
);

CREATE INDEX notification_inbox_member_created_idx
    ON notification_inbox (member_id, created_at DESC, notification_id DESC);

CREATE INDEX notification_inbox_member_unread_idx
    ON notification_inbox (member_id, created_at DESC, notification_id DESC)
    WHERE read_at IS NULL;

CREATE TABLE notification_delivery (
    delivery_id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notification_inbox(notification_id) ON DELETE RESTRICT,
    channel VARCHAR(16) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    provider_message_ref VARCHAR(200),
    last_error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT notification_delivery_channel_valid CHECK (channel IN ('EMAIL', 'IN_APP')),
    CONSTRAINT notification_delivery_status_valid CHECK (status IN ('PENDING', 'DELIVERING', 'DELIVERED', 'SUPPRESSED', 'FAILED')),
    CONSTRAINT notification_delivery_attempt_count_valid CHECK (attempt_count BETWEEN 0 AND 100),
    CONSTRAINT notification_delivery_timestamp_order CHECK (updated_at >= created_at),
    CONSTRAINT notification_delivery_delivered_valid CHECK ((status = 'DELIVERED') = (delivered_at IS NOT NULL))
);

CREATE UNIQUE INDEX notification_delivery_channel_unique
    ON notification_delivery (notification_id, channel);

CREATE INDEX notification_delivery_pending_idx
    ON notification_delivery (next_attempt_at, delivery_id)
    WHERE status IN ('PENDING', 'FAILED');
