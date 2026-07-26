ALTER TABLE circulation_loan
    ADD COLUMN renewal_count INTEGER NOT NULL DEFAULT 0;

-- [jooq ignore start]
ALTER TABLE circulation_loan
    ADD CONSTRAINT ck_circulation_loan_renewal_count
        CHECK (renewal_count BETWEEN 0 AND 100) NOT VALID;
-- [jooq ignore stop]

CREATE TABLE circulation_fine (
    id UUID PRIMARY KEY,
    loan_id UUID NOT NULL REFERENCES circulation_loan (id),
    member_id UUID NOT NULL,
    currency CHAR(3) NOT NULL,
    balance_minor BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_circulation_fine_currency
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_circulation_fine_balance
        CHECK (balance_minor BETWEEN 0 AND 1000000000000),
    CONSTRAINT ck_circulation_fine_status
        CHECK (status IN ('OPEN', 'SETTLED')),
    CONSTRAINT ck_circulation_fine_state
        CHECK (
            (status = 'OPEN' AND balance_minor > 0)
            OR (status = 'SETTLED' AND balance_minor = 0)
        ),
    CONSTRAINT ck_circulation_fine_version CHECK (version >= 0),
    CONSTRAINT ck_circulation_fine_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_circulation_fine_member_status
    ON circulation_fine (member_id, status);

CREATE INDEX ix_circulation_fine_loan
    ON circulation_fine (loan_id);

CREATE TABLE circulation_fine_ledger_entry (
    id UUID PRIMARY KEY,
    fine_id UUID NOT NULL REFERENCES circulation_fine (id),
    fine_version BIGINT NOT NULL,
    entry_type VARCHAR(16) NOT NULL,
    delta_minor BIGINT NOT NULL,
    actor_fingerprint CHAR(64) NOT NULL,
    reason VARCHAR(500),
    external_reference VARCHAR(128),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_circulation_fine_ledger_version UNIQUE (fine_id, fine_version),
    CONSTRAINT ck_circulation_fine_ledger_version CHECK (fine_version >= 0),
    CONSTRAINT ck_circulation_fine_ledger_type
        CHECK (entry_type IN ('ASSESSMENT', 'PAYMENT', 'ADJUSTMENT')),
    CONSTRAINT ck_circulation_fine_ledger_delta
        CHECK (
            delta_minor BETWEEN -1000000000000 AND 1000000000000
            AND delta_minor <> 0
        ),
    CONSTRAINT ck_circulation_fine_ledger_actor
        CHECK (actor_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_circulation_fine_ledger_shape
        CHECK (
            (
                entry_type = 'ASSESSMENT'
                AND delta_minor > 0
                AND reason IS NOT NULL
                AND CHAR_LENGTH(BTRIM(reason)) > 0
                AND external_reference IS NULL
                AND fine_version = 0
            )
            OR
            (
                entry_type = 'PAYMENT'
                AND delta_minor < 0
                AND reason IS NULL
                AND external_reference IS NOT NULL
                AND CHAR_LENGTH(BTRIM(external_reference)) > 0
                AND fine_version > 0
            )
            OR
            (
                entry_type = 'ADJUSTMENT'
                AND reason IS NOT NULL
                AND CHAR_LENGTH(BTRIM(reason)) > 0
                AND external_reference IS NULL
                AND fine_version > 0
            )
        ),
    CONSTRAINT ck_circulation_fine_ledger_timestamps
        CHECK (created_at >= occurred_at)
);

CREATE UNIQUE INDEX uq_circulation_fine_payment_reference
    ON circulation_fine_ledger_entry (external_reference)
    WHERE entry_type = 'PAYMENT';

CREATE INDEX ix_circulation_fine_ledger_fine_occurred
    ON circulation_fine_ledger_entry (fine_id, occurred_at);

-- [jooq ignore start]
CREATE FUNCTION reject_circulation_fine_ledger_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'circulation fine ledger entries are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_circulation_fine_ledger_no_update_delete
    BEFORE UPDATE OR DELETE ON circulation_fine_ledger_entry
    FOR EACH ROW
    EXECUTE FUNCTION reject_circulation_fine_ledger_mutation();

CREATE TRIGGER trg_circulation_fine_ledger_no_truncate
    BEFORE TRUNCATE ON circulation_fine_ledger_entry
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_circulation_fine_ledger_mutation();

CREATE FUNCTION protect_circulation_fine_identity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id <> OLD.id
        OR NEW.loan_id <> OLD.loan_id
        OR NEW.member_id <> OLD.member_id
        OR NEW.currency <> OLD.currency
        OR NEW.created_at <> OLD.created_at
    THEN
        RAISE EXCEPTION 'circulation fine identity fields are immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_circulation_fine_protect_identity
    BEFORE UPDATE ON circulation_fine
    FOR EACH ROW
    EXECUTE FUNCTION protect_circulation_fine_identity();

CREATE FUNCTION validate_circulation_fine_ledger_consistency()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_fine_id UUID;
    current_fine_version BIGINT;
    current_fine_balance BIGINT;
    ledger_count BIGINT;
    minimum_version BIGINT;
    maximum_version BIGINT;
    ledger_balance NUMERIC;
    minimum_running_balance NUMERIC;
    maximum_running_balance NUMERIC;
BEGIN
    IF TG_TABLE_NAME = 'circulation_fine' THEN
        target_fine_id := NEW.id;
    ELSE
        target_fine_id := NEW.fine_id;
    END IF;

    EXECUTE format(
        'SELECT version, balance_minor FROM %I.circulation_fine WHERE id = $1',
        TG_TABLE_SCHEMA
    )
    INTO current_fine_version, current_fine_balance
    USING target_fine_id;

    IF current_fine_version IS NULL THEN
        RAISE EXCEPTION 'circulation fine ledger has no owning fine'
            USING ERRCODE = '23514';
    END IF;

    EXECUTE format(
        'SELECT
            COUNT(*),
            MIN(fine_version),
            MAX(fine_version),
            COALESCE(SUM(delta_minor), 0),
            MIN(running_balance),
            MAX(running_balance)
        FROM (
            SELECT
                fine_version,
                delta_minor,
                SUM(delta_minor) OVER (
                    ORDER BY fine_version
                    ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                ) AS running_balance
            FROM %I.circulation_fine_ledger_entry
            WHERE fine_id = $1
        ) ledger',
        TG_TABLE_SCHEMA
    )
    INTO
        ledger_count,
        minimum_version,
        maximum_version,
        ledger_balance,
        minimum_running_balance,
        maximum_running_balance
    USING target_fine_id;

    IF ledger_count = 0
        OR minimum_version <> 0
        OR maximum_version <> current_fine_version
        OR current_fine_version <> ledger_count - 1
        OR ledger_balance <> current_fine_balance
        OR minimum_running_balance < 0
        OR maximum_running_balance > 1000000000000
    THEN
        RAISE EXCEPTION 'circulation fine balance/version does not match its immutable ledger'
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_circulation_fine_ledger_consistency_from_fine
    AFTER INSERT OR UPDATE ON circulation_fine
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION validate_circulation_fine_ledger_consistency();

CREATE CONSTRAINT TRIGGER trg_circulation_fine_ledger_consistency_from_entry
    AFTER INSERT ON circulation_fine_ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION validate_circulation_fine_ledger_consistency();
-- [jooq ignore stop]

ALTER TABLE circulation_idempotency
    ADD COLUMN renewal_count INTEGER,
    ADD COLUMN fine_id UUID,
    ADD COLUMN currency CHAR(3),
    ADD COLUMN fine_balance_minor BIGINT,
    ADD COLUMN fine_status VARCHAR(16),
    ADD COLUMN ledger_entry_id UUID,
    ADD COLUMN ledger_entry_type VARCHAR(16),
    ADD COLUMN ledger_delta_minor BIGINT,
    ADD COLUMN fine_version BIGINT;

-- [jooq ignore start]
ALTER TABLE circulation_idempotency
    DROP CONSTRAINT ck_circulation_idempotency_actor_operation,
    DROP CONSTRAINT ck_circulation_idempotency_actor_completion,
    ADD CONSTRAINT ck_circulation_idempotency_actor_operation
        CHECK (
            operation IN (
                'REQUEST_LOAN',
                'APPROVE_LOAN',
                'RENEW_LOAN',
                'RETURN_LOAN',
                'ASSESS_FINE',
                'RECORD_FINE_PAYMENT',
                'ADJUST_FINE'
            )
        ) NOT VALID,
    ADD CONSTRAINT ck_circulation_idempotency_renewal_count
        CHECK (renewal_count IS NULL OR renewal_count BETWEEN 0 AND 100) NOT VALID,
    ADD CONSTRAINT ck_circulation_idempotency_currency
        CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$') NOT VALID,
    ADD CONSTRAINT ck_circulation_idempotency_fine_balance
        CHECK (
            fine_balance_minor IS NULL
            OR fine_balance_minor BETWEEN 0 AND 1000000000000
        ) NOT VALID,
    ADD CONSTRAINT ck_circulation_idempotency_fine_status
        CHECK (fine_status IS NULL OR fine_status IN ('OPEN', 'SETTLED')) NOT VALID,
    ADD CONSTRAINT ck_circulation_idempotency_ledger_entry_type
        CHECK (
            ledger_entry_type IS NULL
            OR ledger_entry_type IN ('ASSESSMENT', 'PAYMENT', 'ADJUSTMENT')
        ) NOT VALID,
    ADD CONSTRAINT ck_circulation_idempotency_ledger_delta
        CHECK (
            ledger_delta_minor IS NULL
            OR (
                ledger_delta_minor BETWEEN -1000000000000 AND 1000000000000
                AND ledger_delta_minor <> 0
            )
        ) NOT VALID,
    ADD CONSTRAINT ck_circulation_idempotency_fine_version
        CHECK (fine_version IS NULL OR fine_version >= 0) NOT VALID,
    ADD CONSTRAINT ck_circulation_idempotency_actor_completion
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
                AND renewal_count IS NULL
                AND fine_id IS NULL
                AND currency IS NULL
                AND fine_balance_minor IS NULL
                AND fine_status IS NULL
                AND ledger_entry_id IS NULL
                AND ledger_entry_type IS NULL
                AND ledger_delta_minor IS NULL
                AND fine_version IS NULL
            )
            OR
            (
                completed_at IS NOT NULL
                AND response_status IS NOT NULL
                AND loan_id IS NOT NULL
                AND member_id IS NOT NULL
                AND completed_at >= created_at
                AND (
                    (
                        operation IN (
                            'REQUEST_LOAN',
                            'APPROVE_LOAN',
                            'RENEW_LOAN',
                            'RETURN_LOAN'
                        )
                        AND edition_id IS NOT NULL
                        AND loan_status IS NOT NULL
                        AND requested_at IS NOT NULL
                        AND loan_version IS NOT NULL
                        AND fine_id IS NULL
                        AND currency IS NULL
                        AND fine_balance_minor IS NULL
                        AND fine_status IS NULL
                        AND ledger_entry_id IS NULL
                        AND ledger_entry_type IS NULL
                        AND ledger_delta_minor IS NULL
                        AND fine_version IS NULL
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
                                AND COALESCE(renewal_count, 0) = 0
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
                                operation = 'RENEW_LOAN'
                                AND response_status = 200
                                AND loan_status = 'ACTIVE'
                                AND copy_id IS NOT NULL
                                AND checked_out_at IS NOT NULL
                                AND due_at IS NOT NULL
                                AND due_at > checked_out_at
                                AND returned_at IS NULL
                                AND loan_version > 1
                                AND renewal_count > 0
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
                    OR
                    (
                        operation IN (
                            'ASSESS_FINE',
                            'RECORD_FINE_PAYMENT',
                            'ADJUST_FINE'
                        )
                        AND response_status = CASE
                            WHEN operation = 'ASSESS_FINE' THEN 201
                            ELSE 200
                        END
                        AND edition_id IS NULL
                        AND copy_id IS NULL
                        AND loan_status IS NULL
                        AND requested_at IS NULL
                        AND checked_out_at IS NULL
                        AND due_at IS NULL
                        AND returned_at IS NULL
                        AND loan_version IS NULL
                        AND renewal_count IS NULL
                        AND fine_id IS NOT NULL
                        AND currency IS NOT NULL
                        AND fine_balance_minor IS NOT NULL
                        AND fine_status IS NOT NULL
                        AND ledger_entry_id IS NOT NULL
                        AND ledger_entry_type IS NOT NULL
                        AND ledger_delta_minor IS NOT NULL
                        AND fine_version IS NOT NULL
                        AND (
                            (fine_status = 'OPEN' AND fine_balance_minor > 0)
                            OR (fine_status = 'SETTLED' AND fine_balance_minor = 0)
                        )
                        AND (
                            (
                                operation = 'ASSESS_FINE'
                                AND ledger_entry_type = 'ASSESSMENT'
                                AND ledger_delta_minor > 0
                                AND fine_version = 0
                            )
                            OR
                            (
                                operation = 'RECORD_FINE_PAYMENT'
                                AND ledger_entry_type = 'PAYMENT'
                                AND ledger_delta_minor < 0
                                AND fine_version > 0
                            )
                            OR
                            (
                                operation = 'ADJUST_FINE'
                                AND ledger_entry_type = 'ADJUSTMENT'
                                AND fine_version > 0
                            )
                        )
                    )
                )
            )
        ) NOT VALID;
-- [jooq ignore stop]
