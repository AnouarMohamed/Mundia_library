CREATE TABLE circulation_membership_eligibility_bootstrap (
    bootstrap_id UUID PRIMARY KEY,
    source_revision CHAR(64) NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL,
    actor_fingerprint CHAR(64) NOT NULL,
    member_count INTEGER NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_circulation_eligibility_bootstrap_source_revision
        CHECK (source_revision ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_circulation_eligibility_bootstrap_manifest
        CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_circulation_eligibility_bootstrap_actor
        CHECK (actor_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_circulation_eligibility_bootstrap_member_count
        CHECK (member_count BETWEEN 1 AND 100)
);

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION reject_circulation_eligibility_bootstrap_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'eligibility bootstrap receipts are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_circulation_eligibility_bootstrap_no_update_or_delete
BEFORE UPDATE OR DELETE ON circulation_membership_eligibility_bootstrap
FOR EACH ROW
EXECUTE FUNCTION reject_circulation_eligibility_bootstrap_mutation();

CREATE TRIGGER trg_circulation_eligibility_bootstrap_no_truncate
BEFORE TRUNCATE ON circulation_membership_eligibility_bootstrap
FOR EACH STATEMENT
EXECUTE FUNCTION reject_circulation_eligibility_bootstrap_mutation();
-- [jooq ignore stop]
