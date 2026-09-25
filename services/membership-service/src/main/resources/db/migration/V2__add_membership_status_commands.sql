ALTER TABLE membership_member
    ADD COLUMN aggregate_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT membership_member_version_valid CHECK (aggregate_version >= 0);

CREATE TABLE membership_command_idempotency (
    owner_fingerprint CHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    aggregate_id UUID,
    aggregate_version BIGINT,
    resulting_status VARCHAR(20),
    occurred_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (owner_fingerprint, idempotency_key),
    CONSTRAINT membership_command_owner_fingerprint_valid
        CHECK (owner_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT membership_command_request_fingerprint_valid
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT membership_command_idempotency_key_valid
        CHECK (char_length(idempotency_key) BETWEEN 16 AND 128),
    CONSTRAINT membership_command_operation_valid
        CHECK (operation = 'CHANGE_ACCOUNT_STATUS'),
    CONSTRAINT membership_command_version_valid
        CHECK (aggregate_version IS NULL OR aggregate_version >= 1),
    CONSTRAINT membership_command_resulting_status_valid
        CHECK (resulting_status IS NULL OR resulting_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT membership_command_completion_valid CHECK (
        (completed_at IS NULL AND aggregate_id IS NULL
            AND aggregate_version IS NULL AND resulting_status IS NULL AND occurred_at IS NULL)
        OR
        (completed_at IS NOT NULL AND aggregate_id IS NOT NULL
            AND aggregate_version IS NOT NULL AND resulting_status IS NOT NULL AND occurred_at IS NOT NULL)
    ),
    CONSTRAINT membership_command_time_order_valid CHECK (
        expires_at > created_at AND (completed_at IS NULL OR completed_at >= created_at)
    )
);

CREATE INDEX membership_command_idempotency_expiry_index
    ON membership_command_idempotency (expires_at);

CREATE TABLE membership_audit_entry (
    audit_id UUID PRIMARY KEY,
    member_id UUID NOT NULL REFERENCES membership_member(member_id) ON DELETE RESTRICT,
    aggregate_version BIGINT NOT NULL,
    operation VARCHAR(64) NOT NULL,
    actor_fingerprint CHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    previous_state JSONB NOT NULL,
    resulting_state JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT membership_audit_version_valid CHECK (aggregate_version >= 1),
    CONSTRAINT membership_audit_operation_valid
        CHECK (operation = 'CHANGE_ACCOUNT_STATUS'),
    CONSTRAINT membership_audit_actor_fingerprint_valid
        CHECK (actor_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT membership_audit_reason_valid
        CHECK (reason = btrim(reason) AND char_length(reason) BETWEEN 8 AND 500)
);

CREATE UNIQUE INDEX membership_audit_member_version_index
    ON membership_audit_entry (member_id, aggregate_version);

CREATE TABLE membership_outbox_event (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL REFERENCES membership_member(member_id) ON DELETE RESTRICT,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    event_version INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    CONSTRAINT membership_outbox_version_valid CHECK (aggregate_version >= 1),
    CONSTRAINT membership_outbox_event_type_valid
        CHECK (event_type = 'membership.member.eligibility-changed'),
    CONSTRAINT membership_outbox_event_version_valid CHECK (event_version = 1),
    CONSTRAINT membership_outbox_time_order_valid
        CHECK (published_at IS NULL OR published_at >= occurred_at)
);

CREATE UNIQUE INDEX membership_outbox_member_version_index
    ON membership_outbox_event (aggregate_id, aggregate_version);
CREATE INDEX membership_outbox_unpublished_index
    ON membership_outbox_event (created_at, event_id)
    WHERE published_at IS NULL;
