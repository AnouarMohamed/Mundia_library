-- Validate after V7 releases its brief metadata lock so existing command
-- rows are scanned without blocking normal reads and writes.
-- [jooq ignore start]
ALTER TABLE circulation_idempotency
    VALIDATE CONSTRAINT ck_circulation_idempotency_actor_loan_status,
    VALIDATE CONSTRAINT ck_circulation_idempotency_actor_operation,
    VALIDATE CONSTRAINT ck_circulation_idempotency_actor_completion;
-- [jooq ignore stop]
