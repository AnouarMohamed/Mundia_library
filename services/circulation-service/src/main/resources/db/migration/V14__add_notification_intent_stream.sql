ALTER TABLE outbox_event
    ADD COLUMN event_stream VARCHAR(32) NOT NULL DEFAULT 'DOMAIN';

ALTER TABLE outbox_event
    ADD CONSTRAINT ck_outbox_event_stream
        CHECK (event_stream IN ('DOMAIN', 'NOTIFICATION'));

DROP INDEX uq_outbox_event_aggregate_version;

CREATE UNIQUE INDEX uq_outbox_event_stream_aggregate_version
    ON outbox_event (event_stream, aggregate_type, aggregate_id, aggregate_version);

DROP INDEX ix_outbox_event_aggregate;

CREATE INDEX ix_outbox_event_stream_aggregate
    ON outbox_event (event_stream, aggregate_type, aggregate_id, aggregate_version);
