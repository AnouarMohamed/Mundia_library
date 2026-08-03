-- [jooq ignore start]
ALTER TABLE circulation_copy
    ADD CONSTRAINT ck_circulation_copy_barcode_shape
        CHECK (
            CHAR_LENGTH(barcode) BETWEEN 3 AND 64
            AND barcode ~ '^[A-Za-z0-9][A-Za-z0-9._/-]{2,63}$'
        ) NOT VALID,
    ADD CONSTRAINT ck_circulation_copy_shelf_location_shape
        CHECK (
            shelf_location IS NULL
            OR (
                CHAR_LENGTH(shelf_location) BETWEEN 1 AND 128
                AND shelf_location = BTRIM(shelf_location)
                AND shelf_location !~ '[[:cntrl:]]'
            )
        ) NOT VALID;

ALTER TABLE circulation_copy
    VALIDATE CONSTRAINT ck_circulation_copy_barcode_shape;

ALTER TABLE circulation_copy
    VALIDATE CONSTRAINT ck_circulation_copy_shelf_location_shape;
-- [jooq ignore stop]

CREATE TABLE circulation_inventory_idempotency (
    owner_fingerprint CHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    response_status INTEGER,
    copy_id UUID,
    edition_id UUID,
    branch_id UUID,
    barcode VARCHAR(64),
    copy_status VARCHAR(32),
    shelf_location VARCHAR(128),
    copy_version BIGINT,
    occurred_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (owner_fingerprint, idempotency_key),
    CONSTRAINT ck_circulation_inventory_idempotency_owner
        CHECK (owner_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_circulation_inventory_idempotency_key
        CHECK (
            CHAR_LENGTH(idempotency_key) BETWEEN 16 AND 128
            AND idempotency_key !~ '[^!-~]'
        ),
    CONSTRAINT ck_circulation_inventory_idempotency_operation
        CHECK (
            operation IN (
                'REGISTER_COPY',
                'CHANGE_COPY_CONDITION',
                'RELOCATE_COPY'
            )
        ),
    CONSTRAINT ck_circulation_inventory_idempotency_request_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_circulation_inventory_idempotency_response_status
        CHECK (response_status IS NULL OR response_status IN (200, 201)),
    CONSTRAINT ck_circulation_inventory_idempotency_copy_status
        CHECK (
            copy_status IS NULL
            OR copy_status IN (
                'AVAILABLE',
                'ON_LOAN',
                'RESERVED',
                'LOST',
                'DAMAGED',
                'WITHDRAWN'
            )
        ),
    CONSTRAINT ck_circulation_inventory_idempotency_copy_version
        CHECK (copy_version IS NULL OR copy_version >= 0),
    CONSTRAINT ck_circulation_inventory_idempotency_expiry
        CHECK (expires_at > created_at),
    CONSTRAINT ck_circulation_inventory_idempotency_completion
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
                        AND copy_status = 'AVAILABLE'
                        AND copy_version = 0
                    )
                    OR
                    (
                        operation IN ('CHANGE_COPY_CONDITION', 'RELOCATE_COPY')
                        AND response_status = 200
                        AND copy_version > 0
                    )
                )
            )
        )
);

CREATE INDEX ix_circulation_inventory_idempotency_expires_at
    ON circulation_inventory_idempotency (expires_at);

CREATE TABLE circulation_inventory_audit_entry (
    id UUID PRIMARY KEY,
    copy_id UUID NOT NULL REFERENCES circulation_copy (id),
    copy_version BIGINT NOT NULL,
    operation VARCHAR(32) NOT NULL,
    edition_id UUID NOT NULL,
    barcode VARCHAR(64) NOT NULL,
    previous_branch_id UUID,
    branch_id UUID NOT NULL,
    previous_status VARCHAR(32),
    copy_status VARCHAR(32) NOT NULL,
    previous_shelf_location VARCHAR(128),
    shelf_location VARCHAR(128),
    actor_fingerprint CHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_circulation_inventory_audit_version
        UNIQUE (copy_id, copy_version),
    CONSTRAINT ck_circulation_inventory_audit_version
        CHECK (copy_version >= 0),
    CONSTRAINT ck_circulation_inventory_audit_operation
        CHECK (
            operation IN (
                'REGISTER_COPY',
                'CHANGE_COPY_CONDITION',
                'RELOCATE_COPY'
            )
        ),
    CONSTRAINT ck_circulation_inventory_audit_status
        CHECK (
            copy_status IN (
                'AVAILABLE',
                'ON_LOAN',
                'RESERVED',
                'LOST',
                'DAMAGED',
                'WITHDRAWN'
            )
            AND (
                previous_status IS NULL
                OR previous_status IN (
                    'AVAILABLE',
                    'ON_LOAN',
                    'RESERVED',
                    'LOST',
                    'DAMAGED',
                    'WITHDRAWN'
                )
            )
        ),
    CONSTRAINT ck_circulation_inventory_audit_actor
        CHECK (actor_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_circulation_inventory_audit_reason
        CHECK (
            CHAR_LENGTH(reason) BETWEEN 1 AND 500
            AND reason = BTRIM(reason)
            AND reason !~ '[[:cntrl:]]'
        ),
    CONSTRAINT ck_circulation_inventory_audit_shape
        CHECK (
            (
                operation = 'REGISTER_COPY'
                AND copy_version = 0
                AND copy_status = 'AVAILABLE'
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
        ),
    CONSTRAINT ck_circulation_inventory_audit_timestamps
        CHECK (created_at >= occurred_at)
);

CREATE INDEX ix_circulation_inventory_audit_copy_occurred
    ON circulation_inventory_audit_entry (copy_id, occurred_at);

-- [jooq ignore start]
CREATE FUNCTION reject_circulation_inventory_audit_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'circulation inventory audit entries are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_circulation_inventory_audit_no_update_delete
    BEFORE UPDATE OR DELETE ON circulation_inventory_audit_entry
    FOR EACH ROW
    EXECUTE FUNCTION reject_circulation_inventory_audit_mutation();

CREATE TRIGGER trg_circulation_inventory_audit_no_truncate
    BEFORE TRUNCATE ON circulation_inventory_audit_entry
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_circulation_inventory_audit_mutation();
-- [jooq ignore stop]
