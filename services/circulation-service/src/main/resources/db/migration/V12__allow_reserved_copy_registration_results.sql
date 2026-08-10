-- A copy registered while an edition has a waiting reservation is reserved in
-- the same transaction. Its command result, audit entry, outbox payload, and
-- idempotency result must report the committed RESERVED/version-1 state.
-- [jooq ignore start]
ALTER TABLE circulation_inventory_idempotency
    DROP CONSTRAINT ck_circulation_inventory_idempotency_completion;

ALTER TABLE circulation_inventory_idempotency
    ADD CONSTRAINT ck_circulation_inventory_idempotency_completion
        CHECK (
            (
                completed_at IS NULL
                AND response_status IS NULL
                AND copy_id IS NULL
                AND edition_id IS NULL
                AND branch_id IS NULL
                AND barcode IS NULL
                AND copy_status IS NULL
                AND shelf_location IS NULL
                AND copy_version IS NULL
                AND occurred_at IS NULL
            )
            OR
            (
                completed_at IS NOT NULL
                AND completed_at >= created_at
                AND response_status IS NOT NULL
                AND copy_id IS NOT NULL
                AND edition_id IS NOT NULL
                AND branch_id IS NOT NULL
                AND barcode IS NOT NULL
                AND copy_status IS NOT NULL
                AND copy_version IS NOT NULL
                AND occurred_at IS NOT NULL
                AND occurred_at = completed_at
                AND (
                    (
                        operation = 'REGISTER_COPY'
                        AND response_status = 201
                        AND (
                            (copy_status = 'AVAILABLE' AND copy_version = 0)
                            OR (copy_status = 'RESERVED' AND copy_version = 1)
                        )
                    )
                    OR
                    (
                        operation IN ('CHANGE_COPY_CONDITION', 'RELOCATE_COPY')
                        AND response_status = 200
                        AND copy_version > 0
                    )
                )
            )
        ) NOT VALID;

ALTER TABLE circulation_inventory_idempotency
    VALIDATE CONSTRAINT ck_circulation_inventory_idempotency_completion;

ALTER TABLE circulation_inventory_audit_entry
    DROP CONSTRAINT ck_circulation_inventory_audit_shape;

ALTER TABLE circulation_inventory_audit_entry
    ADD CONSTRAINT ck_circulation_inventory_audit_shape
        CHECK (
            (
                operation = 'REGISTER_COPY'
                AND (
                    (copy_version = 0 AND copy_status = 'AVAILABLE')
                    OR (copy_version = 1 AND copy_status = 'RESERVED')
                )
                AND previous_branch_id IS NULL
                AND previous_status IS NULL
                AND previous_shelf_location IS NULL
            )
            OR
            (
                operation IN ('CHANGE_COPY_CONDITION', 'RELOCATE_COPY')
                AND copy_version > 0
                AND previous_branch_id IS NOT NULL
                AND previous_status IS NOT NULL
            )
        ) NOT VALID;

ALTER TABLE circulation_inventory_audit_entry
    VALIDATE CONSTRAINT ck_circulation_inventory_audit_shape;
-- [jooq ignore stop]
