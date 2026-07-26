CREATE TABLE circulation_idempotency (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    operation VARCHAR(32) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    response_status INTEGER,
    loan_id UUID,
    member_id UUID,
    edition_id UUID,
    copy_id UUID,
    loan_status VARCHAR(32),
    requested_at TIMESTAMPTZ,
    checked_out_at TIMESTAMPTZ,
    due_at TIMESTAMPTZ,
    returned_at TIMESTAMPTZ,
    loan_version BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_circulation_idempotency_operation
        CHECK (operation IN ('REQUEST_LOAN', 'APPROVE_LOAN', 'RETURN_LOAN')),
    CONSTRAINT ck_circulation_idempotency_fingerprint
        CHECK (CHAR_LENGTH(request_fingerprint) = 64),
    CONSTRAINT ck_circulation_idempotency_response_status
        CHECK (response_status IS NULL OR response_status BETWEEN 200 AND 299),
    CONSTRAINT ck_circulation_idempotency_loan_status
        CHECK (
            loan_status IS NULL
            OR loan_status IN ('REQUESTED', 'ACTIVE', 'RETURNED')
        ),
    CONSTRAINT ck_circulation_idempotency_loan_version
        CHECK (loan_version IS NULL OR loan_version >= 0),
    CONSTRAINT ck_circulation_idempotency_expiry
        CHECK (expires_at > created_at),
    CONSTRAINT ck_circulation_idempotency_completion
        CHECK (
            (
                completed_at IS NULL
                AND response_status IS NULL
                AND loan_id IS NULL
                AND member_id IS NULL
                AND edition_id IS NULL
                AND copy_id IS NULL
                AND loan_status IS NULL
                AND requested_at IS NULL
                AND checked_out_at IS NULL
                AND due_at IS NULL
                AND returned_at IS NULL
                AND loan_version IS NULL
            )
            OR
            (
                completed_at IS NOT NULL
                AND response_status IS NOT NULL
                AND loan_id IS NOT NULL
                AND member_id IS NOT NULL
                AND edition_id IS NOT NULL
                AND loan_status IS NOT NULL
                AND requested_at IS NOT NULL
                AND loan_version IS NOT NULL
                AND completed_at >= created_at
                AND (
                    (
                        operation = 'REQUEST_LOAN'
                        AND response_status = 201
                        AND loan_status = 'REQUESTED'
                        AND copy_id IS NULL
                        AND checked_out_at IS NULL
                        AND due_at IS NULL
                        AND returned_at IS NULL
                        AND loan_version = 0
                    )
                    OR
                    (
                        operation = 'APPROVE_LOAN'
                        AND response_status = 200
                        AND loan_status = 'ACTIVE'
                        AND copy_id IS NOT NULL
                        AND checked_out_at IS NOT NULL
                        AND due_at IS NOT NULL
                        AND due_at > checked_out_at
                        AND returned_at IS NULL
                        AND loan_version > 0
                    )
                    OR
                    (
                        operation = 'RETURN_LOAN'
                        AND response_status = 200
                        AND loan_status = 'RETURNED'
                        AND copy_id IS NOT NULL
                        AND checked_out_at IS NOT NULL
                        AND due_at IS NOT NULL
                        AND due_at > checked_out_at
                        AND returned_at IS NOT NULL
                        AND returned_at >= checked_out_at
                        AND loan_version > 1
                    )
                )
            )
        )
);

CREATE INDEX ix_circulation_idempotency_expires_at
    ON circulation_idempotency (expires_at);

CREATE UNIQUE INDEX uq_outbox_event_aggregate_version
    ON outbox_event (aggregate_type, aggregate_id, aggregate_version);

CREATE INDEX ix_circulation_copy_deterministic_allocation
    ON circulation_copy (edition_id, status, barcode, id);
