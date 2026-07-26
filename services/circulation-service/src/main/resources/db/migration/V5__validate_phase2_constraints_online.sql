-- Keep validation in a migration after V4 so PostgreSQL releases V4's brief
-- ACCESS EXCLUSIVE metadata lock before scanning pre-existing rows. CHECK
-- validation permits normal reads and writes while it runs.
-- [jooq ignore start]
ALTER TABLE circulation_loan
    VALIDATE CONSTRAINT ck_circulation_loan_renewal_count;

ALTER TABLE circulation_idempotency
    VALIDATE CONSTRAINT ck_circulation_idempotency_actor_operation,
    VALIDATE CONSTRAINT ck_circulation_idempotency_renewal_count,
    VALIDATE CONSTRAINT ck_circulation_idempotency_currency,
    VALIDATE CONSTRAINT ck_circulation_idempotency_fine_balance,
    VALIDATE CONSTRAINT ck_circulation_idempotency_fine_status,
    VALIDATE CONSTRAINT ck_circulation_idempotency_ledger_entry_type,
    VALIDATE CONSTRAINT ck_circulation_idempotency_ledger_delta,
    VALIDATE CONSTRAINT ck_circulation_idempotency_fine_version,
    VALIDATE CONSTRAINT ck_circulation_idempotency_actor_completion;
-- [jooq ignore stop]
