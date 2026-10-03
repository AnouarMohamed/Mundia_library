ALTER TABLE notification_delivery
    ADD COLUMN provider_outcome VARCHAR(24),
    ADD COLUMN provider_event_at TIMESTAMPTZ,
    ADD CONSTRAINT notification_delivery_provider_outcome_valid
        CHECK (
            provider_outcome IS NULL OR provider_outcome IN (
                'SENT', 'DELAYED', 'DELIVERED', 'BOUNCED', 'COMPLAINED', 'REJECTED', 'RENDERING_FAILED'
            )
        ),
    ADD CONSTRAINT notification_delivery_provider_event_consistent
        CHECK ((provider_outcome IS NULL) = (provider_event_at IS NULL));

CREATE TABLE notification_email_feedback_receipt (
    sns_message_id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES notification_delivery(delivery_id) ON DELETE RESTRICT,
    provider_message_ref VARCHAR(200) NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    event_at TIMESTAMPTZ NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT notification_email_feedback_provider_ref_not_blank CHECK (btrim(provider_message_ref) <> ''),
    CONSTRAINT notification_email_feedback_event_type_valid
        CHECK (
            event_type IN (
                'SEND', 'DELIVERY_DELAY', 'DELIVERY', 'BOUNCE', 'COMPLAINT', 'REJECT',
                'RENDERING_FAILURE', 'OPEN', 'CLICK'
            )
        ),
    CONSTRAINT notification_email_feedback_digest_valid CHECK (payload_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX notification_email_feedback_delivery_idx
    ON notification_email_feedback_receipt (delivery_id, event_at DESC, sns_message_id);
