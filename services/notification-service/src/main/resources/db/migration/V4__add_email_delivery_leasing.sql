ALTER TABLE notification_delivery
    DROP CONSTRAINT notification_delivery_status_valid;

ALTER TABLE notification_delivery
    ADD COLUMN provider VARCHAR(32),
    ADD COLUMN lease_owner VARCHAR(100),
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN last_attempt_at TIMESTAMPTZ,
    ADD COLUMN dead_lettered_at TIMESTAMPTZ,
    ADD CONSTRAINT notification_delivery_status_valid
        CHECK (status IN ('PENDING', 'DELIVERING', 'DELIVERED', 'SUPPRESSED', 'FAILED', 'DEAD_LETTERED')),
    ADD CONSTRAINT notification_delivery_provider_valid
        CHECK (provider IS NULL OR (btrim(provider) <> '' AND length(provider) <= 32)),
    ADD CONSTRAINT notification_delivery_provider_ref_valid
        CHECK (provider_message_ref IS NULL OR btrim(provider_message_ref) <> ''),
    ADD CONSTRAINT notification_delivery_lease_consistent
        CHECK (
            (status = 'DELIVERING') =
            (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
        ),
    ADD CONSTRAINT notification_delivery_dead_letter_consistent
        CHECK ((status = 'DEAD_LETTERED') = (dead_lettered_at IS NOT NULL)),
    ADD CONSTRAINT notification_delivery_next_attempt_consistent
        CHECK ((status IN ('PENDING', 'FAILED')) = (next_attempt_at IS NOT NULL)),
    ADD CONSTRAINT notification_delivery_email_receipt_required
        CHECK (
            channel <> 'EMAIL'
            OR status <> 'DELIVERED'
            OR (provider IS NOT NULL AND provider_message_ref IS NOT NULL)
        );

DROP INDEX notification_delivery_pending_idx;

CREATE INDEX notification_delivery_pending_idx
    ON notification_delivery (next_attempt_at, delivery_id)
    WHERE channel = 'EMAIL' AND status IN ('PENDING', 'FAILED');

CREATE INDEX notification_delivery_expired_lease_idx
    ON notification_delivery (lease_expires_at, delivery_id)
    WHERE channel = 'EMAIL' AND status = 'DELIVERING';

CREATE INDEX notification_delivery_dead_letter_idx
    ON notification_delivery (dead_lettered_at, delivery_id)
    WHERE status = 'DEAD_LETTERED';
