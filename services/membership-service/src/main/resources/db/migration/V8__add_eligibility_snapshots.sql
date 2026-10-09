CREATE TABLE membership_eligibility_snapshot (
    snapshot_id UUID PRIMARY KEY,
    source_revision CHAR(64) NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL,
    actor_fingerprint CHAR(64) NOT NULL,
    member_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT membership_eligibility_snapshot_source_revision_valid
        CHECK (source_revision ~ '^[0-9a-f]{64}$'),
    CONSTRAINT membership_eligibility_snapshot_manifest_valid
        CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT membership_eligibility_snapshot_actor_valid
        CHECK (actor_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT membership_eligibility_snapshot_count_valid
        CHECK (member_count BETWEEN 1 AND 10000)
);

CREATE TABLE membership_eligibility_snapshot_item (
    snapshot_id UUID NOT NULL
        REFERENCES membership_eligibility_snapshot(snapshot_id) ON DELETE RESTRICT,
    member_id UUID NOT NULL,
    eligibility_status VARCHAR(20) NOT NULL,
    reason_code VARCHAR(64),
    source_version BIGINT NOT NULL,
    source_occurred_at TIMESTAMPTZ NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    PRIMARY KEY (snapshot_id, member_id),
    CONSTRAINT membership_eligibility_snapshot_item_status_valid
        CHECK (eligibility_status IN ('ELIGIBLE', 'INELIGIBLE', 'SUSPENDED')),
    CONSTRAINT membership_eligibility_snapshot_item_reason_valid CHECK (
        (eligibility_status = 'ELIGIBLE' AND reason_code IS NULL)
        OR (eligibility_status <> 'ELIGIBLE' AND reason_code IS NOT NULL)
    ),
    CONSTRAINT membership_eligibility_snapshot_item_reason_shape_valid
        CHECK (reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT membership_eligibility_snapshot_item_version_valid
        CHECK (source_version >= 0),
    CONSTRAINT membership_eligibility_snapshot_item_hash_valid
        CHECK (content_sha256 ~ '^[0-9a-f]{64}$')
);

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION reject_membership_eligibility_snapshot_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'membership eligibility snapshots are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_membership_eligibility_snapshot_no_update_or_delete
BEFORE UPDATE OR DELETE ON membership_eligibility_snapshot
FOR EACH ROW EXECUTE FUNCTION reject_membership_eligibility_snapshot_mutation();

CREATE TRIGGER trg_membership_eligibility_snapshot_no_truncate
BEFORE TRUNCATE ON membership_eligibility_snapshot
FOR EACH STATEMENT EXECUTE FUNCTION reject_membership_eligibility_snapshot_mutation();

CREATE TRIGGER trg_membership_eligibility_snapshot_item_no_update_or_delete
BEFORE UPDATE OR DELETE ON membership_eligibility_snapshot_item
FOR EACH ROW EXECUTE FUNCTION reject_membership_eligibility_snapshot_mutation();

CREATE TRIGGER trg_membership_eligibility_snapshot_item_no_truncate
BEFORE TRUNCATE ON membership_eligibility_snapshot_item
FOR EACH STATEMENT EXECUTE FUNCTION reject_membership_eligibility_snapshot_mutation();
-- [jooq ignore stop]
