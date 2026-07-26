CREATE TABLE circulation_copy (
    id UUID PRIMARY KEY,
    edition_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    barcode VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    shelf_location VARCHAR(128),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_circulation_copy_barcode UNIQUE (barcode),
    CONSTRAINT ck_circulation_copy_status
        CHECK (status IN ('AVAILABLE', 'ON_LOAN', 'RESERVED', 'LOST', 'DAMAGED', 'WITHDRAWN')),
    CONSTRAINT ck_circulation_copy_version CHECK (version >= 0)
);

CREATE INDEX ix_circulation_copy_edition_status
    ON circulation_copy (edition_id, status);

CREATE INDEX ix_circulation_copy_branch_status
    ON circulation_copy (branch_id, status);

CREATE TABLE circulation_loan (
    id UUID PRIMARY KEY,
    member_id UUID NOT NULL,
    edition_id UUID NOT NULL,
    copy_id UUID REFERENCES circulation_copy (id),
    status VARCHAR(32) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    checked_out_at TIMESTAMPTZ,
    due_at TIMESTAMPTZ,
    returned_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_circulation_loan_status
        CHECK (status IN ('REQUESTED', 'ACTIVE', 'RETURNED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_circulation_loan_version CHECK (version >= 0),
    CONSTRAINT ck_circulation_loan_state
        CHECK (
            (status = 'REQUESTED'
                AND copy_id IS NULL
                AND checked_out_at IS NULL
                AND due_at IS NULL
                AND returned_at IS NULL
                AND rejected_at IS NULL)
            OR
            (status = 'ACTIVE'
                AND copy_id IS NOT NULL
                AND checked_out_at IS NOT NULL
                AND due_at IS NOT NULL
                AND returned_at IS NULL
                AND rejected_at IS NULL)
            OR
            (status = 'RETURNED'
                AND copy_id IS NOT NULL
                AND checked_out_at IS NOT NULL
                AND due_at IS NOT NULL
                AND returned_at IS NOT NULL
                AND rejected_at IS NULL)
            OR
            (status = 'REJECTED'
                AND copy_id IS NULL
                AND checked_out_at IS NULL
                AND due_at IS NULL
                AND returned_at IS NULL
                AND rejected_at IS NOT NULL)
            OR
            (status = 'CANCELLED'
                AND copy_id IS NULL
                AND checked_out_at IS NULL
                AND due_at IS NULL
                AND returned_at IS NULL)
        ),
    CONSTRAINT ck_circulation_loan_due_after_checkout
        CHECK (due_at IS NULL OR checked_out_at IS NULL OR due_at > checked_out_at),
    CONSTRAINT ck_circulation_loan_return_after_checkout
        CHECK (returned_at IS NULL OR checked_out_at IS NULL OR returned_at >= checked_out_at)
);

CREATE UNIQUE INDEX uq_circulation_loan_active_copy
    ON circulation_loan (copy_id)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX uq_circulation_loan_open_member_edition
    ON circulation_loan (member_id, edition_id)
    WHERE status IN ('REQUESTED', 'ACTIVE');

CREATE INDEX ix_circulation_loan_member_status
    ON circulation_loan (member_id, status);

CREATE INDEX ix_circulation_loan_due_active
    ON circulation_loan (due_at)
    WHERE status = 'ACTIVE';

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(160) NOT NULL,
    event_version INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    trace_id VARCHAR(64),
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_outbox_aggregate_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_outbox_event_version CHECK (event_version > 0)
);

CREATE INDEX ix_outbox_event_aggregate
    ON outbox_event (aggregate_type, aggregate_id, aggregate_version);

CREATE INDEX ix_outbox_event_created_at
    ON outbox_event (created_at);
