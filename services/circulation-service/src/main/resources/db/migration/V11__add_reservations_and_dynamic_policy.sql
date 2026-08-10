CREATE TABLE circulation_policy_revision (
    revision_id UUID PRIMARY KEY,
    sequence BIGINT NOT NULL UNIQUE,
    default_loan_period_seconds BIGINT NOT NULL,
    renewal_period_seconds BIGINT NOT NULL,
    maximum_renewals INTEGER NOT NULL,
    fine_currency CHAR(3) NOT NULL,
    reservation_hold_period_seconds BIGINT NOT NULL,
    maximum_active_reservations INTEGER NOT NULL,
    actor_fingerprint CHAR(64) NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_circulation_policy_sequence CHECK (sequence >= 0),
    CONSTRAINT ck_circulation_policy_loan_period
        CHECK (default_loan_period_seconds BETWEEN 1 AND 31536000),
    CONSTRAINT ck_circulation_policy_renewal_period
        CHECK (renewal_period_seconds BETWEEN 1 AND 31536000),
    CONSTRAINT ck_circulation_policy_maximum_renewals
        CHECK (maximum_renewals BETWEEN 1 AND 100),
    CONSTRAINT ck_circulation_policy_currency
        CHECK (fine_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_circulation_policy_hold_period
        CHECK (reservation_hold_period_seconds BETWEEN 1 AND 2592000),
    CONSTRAINT ck_circulation_policy_maximum_reservations
        CHECK (maximum_active_reservations BETWEEN 1 AND 100),
    CONSTRAINT ck_circulation_policy_actor
        CHECK (actor_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_circulation_policy_timestamps CHECK (created_at >= effective_at)
);

CREATE TABLE circulation_policy_current (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE,
    revision_id UUID NOT NULL REFERENCES circulation_policy_revision (revision_id),
    CONSTRAINT ck_circulation_policy_singleton CHECK (singleton)
);

INSERT INTO circulation_policy_revision (
    revision_id,
    sequence,
    default_loan_period_seconds,
    renewal_period_seconds,
    maximum_renewals,
    fine_currency,
    reservation_hold_period_seconds,
    maximum_active_reservations,
    actor_fingerprint,
    effective_at
) VALUES (
    '00000000-0000-0000-0000-000000000001',
    0,
    1209600,
    1209600,
    2,
    'MAD',
    172800,
    10,
    '0000000000000000000000000000000000000000000000000000000000000000',
    CURRENT_TIMESTAMP
);

INSERT INTO circulation_policy_current (singleton, revision_id)
VALUES (TRUE, '00000000-0000-0000-0000-000000000001');

CREATE TABLE circulation_policy_idempotency (
    owner_fingerprint CHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    revision_id UUID REFERENCES circulation_policy_revision (revision_id),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (owner_fingerprint, idempotency_key),
    CONSTRAINT ck_circulation_policy_idempotency_owner
        CHECK (owner_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_circulation_policy_idempotency_key
        CHECK (
            CHAR_LENGTH(idempotency_key) BETWEEN 16 AND 128
            AND idempotency_key !~ '[^!-~]'
        ),
    CONSTRAINT ck_circulation_policy_idempotency_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_circulation_policy_idempotency_expiry
        CHECK (expires_at > created_at),
    CONSTRAINT ck_circulation_policy_idempotency_completion
        CHECK (
            (completed_at IS NULL AND revision_id IS NULL)
            OR
            (completed_at IS NOT NULL AND revision_id IS NOT NULL AND completed_at >= created_at)
        )
);

CREATE INDEX ix_circulation_policy_idempotency_expires_at
    ON circulation_policy_idempotency (expires_at);

CREATE TABLE circulation_reservation (
    id UUID PRIMARY KEY,
    member_id UUID NOT NULL,
    edition_id UUID NOT NULL,
    copy_id UUID REFERENCES circulation_copy (id),
    status VARCHAR(16) NOT NULL,
    placed_at TIMESTAMPTZ NOT NULL,
    ready_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    fulfilled_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_circulation_reservation_status
        CHECK (status IN ('WAITING', 'READY', 'FULFILLED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_circulation_reservation_version CHECK (version >= 0),
    CONSTRAINT ck_circulation_reservation_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_circulation_reservation_state CHECK (
        (status = 'WAITING'
            AND copy_id IS NULL
            AND ready_at IS NULL
            AND expires_at IS NULL
            AND fulfilled_at IS NULL
            AND cancelled_at IS NULL)
        OR
        (status = 'READY'
            AND copy_id IS NOT NULL
            AND ready_at IS NOT NULL
            AND expires_at IS NOT NULL
            AND ready_at >= placed_at
            AND expires_at > ready_at
            AND fulfilled_at IS NULL
            AND cancelled_at IS NULL)
        OR
        (status = 'FULFILLED'
            AND copy_id IS NOT NULL
            AND ready_at IS NOT NULL
            AND expires_at IS NOT NULL
            AND ready_at >= placed_at
            AND expires_at > ready_at
            AND fulfilled_at IS NOT NULL
            AND fulfilled_at >= ready_at
            AND fulfilled_at <= expires_at
            AND cancelled_at IS NULL)
        OR
        (status = 'CANCELLED'
            AND fulfilled_at IS NULL
            AND cancelled_at IS NOT NULL
            AND cancelled_at >= placed_at
            AND (
                (copy_id IS NULL AND ready_at IS NULL AND expires_at IS NULL)
                OR
                (copy_id IS NOT NULL
                    AND ready_at IS NOT NULL
                    AND expires_at IS NOT NULL
                    AND ready_at >= placed_at
                    AND expires_at > ready_at)
            ))
        OR
        (status = 'EXPIRED'
            AND copy_id IS NOT NULL
            AND ready_at IS NOT NULL
            AND expires_at IS NOT NULL
            AND ready_at >= placed_at
            AND expires_at > ready_at
            AND expires_at <= updated_at
            AND fulfilled_at IS NULL
            AND cancelled_at IS NULL)
    )
);

CREATE UNIQUE INDEX uq_circulation_reservation_open_member_edition
    ON circulation_reservation (member_id, edition_id)
    WHERE status IN ('WAITING', 'READY');

CREATE UNIQUE INDEX uq_circulation_reservation_ready_copy
    ON circulation_reservation (copy_id)
    WHERE status = 'READY';

CREATE INDEX ix_circulation_reservation_queue
    ON circulation_reservation (edition_id, placed_at, id)
    WHERE status = 'WAITING';

CREATE INDEX ix_circulation_reservation_member_status
    ON circulation_reservation (member_id, status);

CREATE INDEX ix_circulation_reservation_expiry
    ON circulation_reservation (expires_at, id)
    WHERE status = 'READY';

CREATE TABLE circulation_reservation_idempotency (
    owner_fingerprint CHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    operation VARCHAR(24) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    response_status INTEGER,
    reservation_id UUID,
    member_id UUID,
    edition_id UUID,
    copy_id UUID,
    reservation_status VARCHAR(16),
    placed_at TIMESTAMPTZ,
    ready_at TIMESTAMPTZ,
    expires_at_result TIMESTAMPTZ,
    fulfilled_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    reservation_version BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (owner_fingerprint, idempotency_key),
    CONSTRAINT ck_circulation_reservation_idempotency_owner
        CHECK (owner_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_circulation_reservation_idempotency_key
        CHECK (
            CHAR_LENGTH(idempotency_key) BETWEEN 16 AND 128
            AND idempotency_key !~ '[^!-~]'
        ),
    CONSTRAINT ck_circulation_reservation_idempotency_operation
        CHECK (operation IN ('PLACE', 'CANCEL', 'FULFILL', 'EXPIRE')),
    CONSTRAINT ck_circulation_reservation_idempotency_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_circulation_reservation_idempotency_response
        CHECK (response_status IS NULL OR response_status IN (200, 201)),
    CONSTRAINT ck_circulation_reservation_idempotency_expiry
        CHECK (expires_at > created_at),
    CONSTRAINT ck_circulation_reservation_idempotency_completion CHECK (
        (completed_at IS NULL
            AND response_status IS NULL
            AND reservation_id IS NULL
            AND member_id IS NULL
            AND edition_id IS NULL
            AND reservation_status IS NULL
            AND placed_at IS NULL
            AND reservation_version IS NULL)
        OR
        (completed_at IS NOT NULL
            AND response_status IS NOT NULL
            AND reservation_id IS NOT NULL
            AND member_id IS NOT NULL
            AND edition_id IS NOT NULL
            AND reservation_status IS NOT NULL
            AND placed_at IS NOT NULL
            AND reservation_version IS NOT NULL
            AND completed_at >= created_at)
    )
);

CREATE INDEX ix_circulation_reservation_idempotency_expires_at
    ON circulation_reservation_idempotency (expires_at);

CREATE TABLE circulation_rate_limit_bucket (
    principal_fingerprint CHAR(64) NOT NULL,
    bucket_key VARCHAR(32) NOT NULL,
    request_count INTEGER NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (principal_fingerprint, bucket_key),
    CONSTRAINT ck_circulation_rate_limit_principal
        CHECK (principal_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_circulation_rate_limit_bucket_key
        CHECK (bucket_key ~ '^[a-z][a-z0-9-]{0,31}$'),
    CONSTRAINT ck_circulation_rate_limit_count
        CHECK (request_count BETWEEN 1 AND 1000000),
    CONSTRAINT ck_circulation_rate_limit_window
        CHECK (expires_at > window_started_at)
);

CREATE INDEX ix_circulation_rate_limit_expiry
    ON circulation_rate_limit_bucket (expires_at);

-- [jooq ignore start]
CREATE FUNCTION protect_circulation_policy_revision()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'circulation policy revisions are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_circulation_policy_revision_no_update_delete
    BEFORE UPDATE OR DELETE ON circulation_policy_revision
    FOR EACH ROW EXECUTE FUNCTION protect_circulation_policy_revision();

CREATE TRIGGER trg_circulation_policy_revision_no_truncate
    BEFORE TRUNCATE ON circulation_policy_revision
    FOR EACH STATEMENT EXECUTE FUNCTION protect_circulation_policy_revision();
-- [jooq ignore stop]
