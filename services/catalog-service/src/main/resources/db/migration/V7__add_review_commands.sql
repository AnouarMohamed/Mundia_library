ALTER TABLE catalog_review
    ADD COLUMN aggregate_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT catalog_review_version_valid CHECK (aggregate_version >= 0);

ALTER TABLE catalog_command_idempotency
    DROP CONSTRAINT catalog_command_operation_valid;

ALTER TABLE catalog_command_idempotency
    DROP CONSTRAINT catalog_command_aggregate_type_valid;

ALTER TABLE catalog_command_idempotency
    ADD CONSTRAINT catalog_command_operation_valid CHECK (
        operation IN (
            'CREATE_WORK',
            'UPDATE_WORK',
            'CREATE_EDITION',
            'UPDATE_EDITION',
            'SET_EDITION_ACTIVE',
            'CREATE_REVIEW',
            'UPDATE_REVIEW',
            'DELETE_REVIEW'
        )
    );

ALTER TABLE catalog_command_idempotency
    ADD CONSTRAINT catalog_command_aggregate_type_valid
        CHECK (aggregate_type IS NULL OR aggregate_type IN ('work', 'edition', 'review'));

ALTER TABLE catalog_audit_entry
    DROP CONSTRAINT catalog_audit_aggregate_type_valid;

ALTER TABLE catalog_audit_entry
    DROP CONSTRAINT catalog_audit_operation_valid;

ALTER TABLE catalog_audit_entry
    ADD CONSTRAINT catalog_audit_aggregate_type_valid
        CHECK (aggregate_type IN ('work', 'edition', 'review'));

ALTER TABLE catalog_audit_entry
    ADD CONSTRAINT catalog_audit_operation_valid CHECK (
        operation IN (
            'CREATE_WORK',
            'UPDATE_WORK',
            'CREATE_EDITION',
            'UPDATE_EDITION',
            'SET_EDITION_ACTIVE',
            'CREATE_REVIEW',
            'UPDATE_REVIEW',
            'DELETE_REVIEW'
        )
    );

ALTER TABLE catalog_outbox_event
    DROP CONSTRAINT catalog_outbox_aggregate_type_valid;

ALTER TABLE catalog_outbox_event
    DROP CONSTRAINT catalog_outbox_event_type_valid;

ALTER TABLE catalog_outbox_event
    ADD CONSTRAINT catalog_outbox_aggregate_type_valid
        CHECK (aggregate_type IN ('work', 'edition', 'review'));

ALTER TABLE catalog_outbox_event
    ADD CONSTRAINT catalog_outbox_event_type_valid CHECK (
        event_type IN (
            'catalog.work.created',
            'catalog.work.updated',
            'catalog.edition.created',
            'catalog.edition.updated',
            'catalog.edition.activation-changed',
            'catalog.review.created',
            'catalog.review.updated',
            'catalog.review.deleted'
        )
    );
