CREATE TABLE notification_email_suppression (
    member_id UUID PRIMARY KEY,
    reason VARCHAR(32) NOT NULL,
    source_delivery_id UUID NOT NULL REFERENCES notification_delivery(delivery_id) ON DELETE RESTRICT,
    source_sns_message_id UUID NOT NULL UNIQUE
        REFERENCES notification_email_feedback_receipt(sns_message_id) ON DELETE RESTRICT,
    source_event_at TIMESTAMPTZ NOT NULL,
    suppressed_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT notification_email_suppression_reason_valid
        CHECK (reason IN ('PERMANENT_BOUNCE', 'COMPLAINT')),
    CONSTRAINT notification_email_suppression_timestamps_valid
        CHECK (suppressed_at <= updated_at AND source_event_at <= updated_at)
);

CREATE INDEX notification_email_suppression_updated_idx
    ON notification_email_suppression (updated_at DESC, member_id);
