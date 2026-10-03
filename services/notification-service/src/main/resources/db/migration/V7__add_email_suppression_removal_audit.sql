CREATE TABLE notification_email_suppression_removal_audit (
    request_id UUID PRIMARY KEY,
    member_id UUID NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    justification VARCHAR(500) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    removed BOOLEAN NOT NULL,
    previous_reason VARCHAR(32),
    previous_source_delivery_id UUID,
    previous_source_sns_message_id UUID,
    previous_source_event_at TIMESTAMPTZ,
    previous_suppressed_at TIMESTAMPTZ,
    performed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT notification_email_suppression_removal_actor_valid
        CHECK (actor_subject = BTRIM(actor_subject) AND LENGTH(actor_subject) > 0),
    CONSTRAINT notification_email_suppression_removal_justification_valid
        CHECK (justification = BTRIM(justification) AND LENGTH(justification) BETWEEN 20 AND 500),
    CONSTRAINT notification_email_suppression_removal_digest_valid
        CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT notification_email_suppression_removal_snapshot_valid
        CHECK (
            (removed AND previous_reason IS NOT NULL AND previous_source_delivery_id IS NOT NULL
                AND previous_source_sns_message_id IS NOT NULL AND previous_source_event_at IS NOT NULL
                AND previous_suppressed_at IS NOT NULL)
            OR
            (NOT removed AND previous_reason IS NULL AND previous_source_delivery_id IS NULL
                AND previous_source_sns_message_id IS NULL AND previous_source_event_at IS NULL
                AND previous_suppressed_at IS NULL)
        ),
    CONSTRAINT notification_email_suppression_removal_reason_valid
        CHECK (previous_reason IS NULL OR previous_reason IN ('PERMANENT_BOUNCE', 'COMPLAINT'))
);

CREATE INDEX notification_email_suppression_removal_member_idx
    ON notification_email_suppression_removal_audit (member_id, performed_at DESC, request_id);
