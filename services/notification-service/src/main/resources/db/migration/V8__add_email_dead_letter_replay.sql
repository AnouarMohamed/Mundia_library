ALTER TABLE notification_delivery
    ADD COLUMN replay_count INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT notification_delivery_replay_count_valid
        CHECK (replay_count BETWEEN 0 AND 3);

CREATE TABLE notification_email_dead_letter_replay_audit (
    request_id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    justification VARCHAR(500) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    previous_attempt_count INTEGER NOT NULL,
    previous_last_error_code VARCHAR(64) NOT NULL,
    previous_dead_lettered_at TIMESTAMPTZ NOT NULL,
    replay_count INTEGER NOT NULL,
    queued_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT notification_email_dead_letter_replay_actor_valid
        CHECK (actor_subject = BTRIM(actor_subject) AND LENGTH(actor_subject) > 0),
    CONSTRAINT notification_email_dead_letter_replay_justification_valid
        CHECK (justification = BTRIM(justification) AND LENGTH(justification) BETWEEN 20 AND 500),
    CONSTRAINT notification_email_dead_letter_replay_digest_valid
        CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT notification_email_dead_letter_replay_attempt_valid
        CHECK (previous_attempt_count > 0),
    CONSTRAINT notification_email_dead_letter_replay_count_valid
        CHECK (replay_count BETWEEN 1 AND 3)
);

CREATE INDEX notification_email_dead_letter_replay_delivery_idx
    ON notification_email_dead_letter_replay_audit (delivery_id, queued_at DESC, request_id);
