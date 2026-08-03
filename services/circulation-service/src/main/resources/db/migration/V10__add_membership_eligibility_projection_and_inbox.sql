CREATE TABLE circulation_member_eligibility (
    member_id UUID PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64),
    source_version BIGINT NOT NULL,
    source_occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_circulation_member_eligibility_status
        CHECK (status IN ('ELIGIBLE', 'INELIGIBLE', 'SUSPENDED')),
    CONSTRAINT ck_circulation_member_eligibility_reason
        CHECK (
            (status = 'ELIGIBLE' AND reason_code IS NULL)
            OR
            (status IN ('INELIGIBLE', 'SUSPENDED') AND reason_code IS NOT NULL)
        ),
    CONSTRAINT ck_circulation_member_eligibility_reason_shape
        CHECK (
            reason_code IS NULL
            OR reason_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
        ),
    CONSTRAINT ck_circulation_member_eligibility_source_version
        CHECK (source_version >= 0),
    CONSTRAINT ck_circulation_member_eligibility_timestamps
        CHECK (created_at <= updated_at)
);

CREATE INDEX ix_circulation_member_eligibility_status
    ON circulation_member_eligibility (status, member_id);

CREATE TABLE circulation_consumer_inbox (
    consumer_name VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(160) NOT NULL,
    event_version INTEGER NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    disposition VARCHAR(32) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT uq_circulation_consumer_inbox_aggregate_version
        UNIQUE (consumer_name, aggregate_type, aggregate_id, aggregate_version),
    CONSTRAINT ck_circulation_consumer_inbox_consumer_name
        CHECK (
            consumer_name !~ '[[:cntrl:]]'
            AND consumer_name = btrim(consumer_name)
            AND consumer_name <> ''
        ),
    CONSTRAINT ck_circulation_consumer_inbox_event_type
        CHECK (
            event_type !~ '[[:cntrl:]]'
            AND event_type = btrim(event_type)
            AND event_type <> ''
        ),
    CONSTRAINT ck_circulation_consumer_inbox_event_version
        CHECK (event_version > 0),
    CONSTRAINT ck_circulation_consumer_inbox_aggregate_type
        CHECK (
            aggregate_type !~ '[[:cntrl:]]'
            AND aggregate_type = btrim(aggregate_type)
            AND aggregate_type <> ''
        ),
    CONSTRAINT ck_circulation_consumer_inbox_aggregate_version
        CHECK (aggregate_version >= 0),
    CONSTRAINT ck_circulation_consumer_inbox_payload_sha256
        CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_circulation_consumer_inbox_disposition
        CHECK (disposition IN ('APPLIED', 'STALE')),
    CONSTRAINT ck_circulation_consumer_inbox_timestamps
        CHECK (received_at <= processed_at)
);

CREATE INDEX ix_circulation_consumer_inbox_processed_at
    ON circulation_consumer_inbox (processed_at);

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION reject_circulation_consumer_inbox_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'circulation consumer inbox entries are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_circulation_consumer_inbox_no_update_or_delete
BEFORE UPDATE OR DELETE ON circulation_consumer_inbox
FOR EACH ROW
EXECUTE FUNCTION reject_circulation_consumer_inbox_mutation();

CREATE TRIGGER trg_circulation_consumer_inbox_no_truncate
BEFORE TRUNCATE ON circulation_consumer_inbox
FOR EACH STATEMENT
EXECUTE FUNCTION reject_circulation_consumer_inbox_mutation();

CREATE OR REPLACE FUNCTION guard_circulation_member_eligibility_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'circulation eligibility projections cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.member_id <> OLD.member_id
        OR NEW.created_at <> OLD.created_at
        OR NEW.source_version <> OLD.source_version + 1
        OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'invalid circulation eligibility projection transition'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_circulation_member_eligibility_guard_update_or_delete
BEFORE UPDATE OR DELETE ON circulation_member_eligibility
FOR EACH ROW
EXECUTE FUNCTION guard_circulation_member_eligibility_mutation();

CREATE OR REPLACE FUNCTION reject_circulation_member_eligibility_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'circulation eligibility projections cannot be truncated'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_circulation_member_eligibility_no_truncate
BEFORE TRUNCATE ON circulation_member_eligibility
FOR EACH STATEMENT
EXECUTE FUNCTION reject_circulation_member_eligibility_truncate();
-- [jooq ignore stop]
