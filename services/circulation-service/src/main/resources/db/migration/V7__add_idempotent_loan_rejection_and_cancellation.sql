-- Add staff rejection and member cancellation as first-class, replay-safe
-- circulation commands. Transition timestamps remain authoritative on the
-- loan/event envelope; the idempotency snapshot contains the stable command
-- result returned to callers.
-- [jooq ignore start]
ALTER TABLE circulation_idempotency
    DROP CONSTRAINT ck_circulation_idempotency_actor_loan_status,
    DROP CONSTRAINT ck_circulation_idempotency_actor_operation,
    DROP CONSTRAINT ck_circulation_idempotency_actor_completion,
    ADD CONSTRAINT ck_circulation_idempotency_actor_loan_status
        CHECK (
            loan_status IS NULL
            OR loan_status IN (
                'REQUESTED',
                'ACTIVE',
                'RETURNED',
                'REJECTED',
                'CANCELLED'
            )
        ) NOT VALID,
    ADD CONSTRAINT ck_circulation_idempotency_actor_operation
        CHECK (
            operation IN (
                'REQUEST_LOAN',
                'APPROVE_LOAN',
                'REJECT_LOAN',
                'CANCEL_LOAN',
                'RENEW_LOAN',
                'RETURN_LOAN',
                'ASSESS_FINE',
                'RECORD_FINE_PAYMENT',
                'ADJUST_FINE'
            )
        ) NOT VALID,
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
                            'REJECT_LOAN',
                            'CANCEL_LOAN',
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
                                operation = 'REJECT_LOAN'
                                AND response_status = 200
                                AND loan_status = 'REJECTED'
                                AND copy_id IS NULL
                                AND checked_out_at IS NULL
                                AND due_at IS NULL
                                AND returned_at IS NULL
                                AND loan_version > 0
                                AND COALESCE(renewal_count, 0) = 0
                            )
                            OR
                            (
                                operation = 'CANCEL_LOAN'
                                AND response_status = 200
                                AND loan_status = 'CANCELLED'
                                AND copy_id IS NULL
                                AND checked_out_at IS NULL
                                AND due_at IS NULL
                                AND returned_at IS NULL
                                AND loan_version > 0
                                AND COALESCE(renewal_count, 0) = 0
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
