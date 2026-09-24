ALTER TABLE catalog_command_idempotency
    DROP CONSTRAINT catalog_command_operation_valid;

ALTER TABLE catalog_command_idempotency
    DROP CONSTRAINT catalog_command_response_status_valid;

ALTER TABLE catalog_command_idempotency
    ADD CONSTRAINT catalog_command_operation_valid CHECK (
        operation IN (
            'CREATE_WORK',
            'UPDATE_WORK',
            'CREATE_EDITION',
            'UPDATE_EDITION',
            'SET_EDITION_ACTIVE'
        )
    );

ALTER TABLE catalog_command_idempotency
    ADD CONSTRAINT catalog_command_response_status_valid
        CHECK (response_status IS NULL OR response_status IN (200, 201));

ALTER TABLE catalog_audit_entry
    DROP CONSTRAINT catalog_audit_operation_valid;

ALTER TABLE catalog_audit_entry
    ADD CONSTRAINT catalog_audit_operation_valid CHECK (
        operation IN (
            'CREATE_WORK',
            'UPDATE_WORK',
            'CREATE_EDITION',
            'UPDATE_EDITION',
            'SET_EDITION_ACTIVE'
        )
    );

ALTER TABLE catalog_outbox_event
    DROP CONSTRAINT catalog_outbox_event_type_valid;

ALTER TABLE catalog_outbox_event
    ADD CONSTRAINT catalog_outbox_event_type_valid CHECK (
        event_type IN (
            'catalog.work.created',
            'catalog.work.updated',
            'catalog.edition.created',
            'catalog.edition.updated',
            'catalog.edition.activation-changed'
        )
    );
