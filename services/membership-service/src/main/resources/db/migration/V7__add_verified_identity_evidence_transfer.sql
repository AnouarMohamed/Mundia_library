ALTER TABLE membership_identity_evidence
    ADD COLUMN verification_status VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNATTESTED',
    ADD COLUMN source_reference_sha256 CHAR(64),
    ADD COLUMN scan_attestation_sha256 CHAR(64),
    ADD COLUMN verified_at TIMESTAMPTZ,
    ADD COLUMN retention_expires_at TIMESTAMPTZ,
    ADD CONSTRAINT membership_identity_evidence_verification_status_valid
        CHECK (verification_status IN ('LEGACY_UNATTESTED', 'VERIFIED')),
    ADD CONSTRAINT membership_identity_evidence_source_reference_valid
        CHECK (source_reference_sha256 IS NULL OR source_reference_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT membership_identity_evidence_scan_attestation_valid
        CHECK (scan_attestation_sha256 IS NULL OR scan_attestation_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT membership_identity_evidence_verification_shape_valid
        CHECK (
            (verification_status = 'LEGACY_UNATTESTED'
                AND source_reference_sha256 IS NULL
                AND scan_attestation_sha256 IS NULL
                AND verified_at IS NULL
                AND retention_expires_at IS NULL)
            OR
            (verification_status = 'VERIFIED'
                AND source_reference_sha256 IS NOT NULL
                AND scan_attestation_sha256 IS NOT NULL
                AND verified_at IS NOT NULL
                AND uploaded_at = verified_at
                AND retention_expires_at > verified_at)
        );

CREATE INDEX membership_identity_evidence_retention_index
    ON membership_identity_evidence (retention_expires_at, evidence_id)
    WHERE verification_status = 'VERIFIED';

CREATE TABLE membership_identity_evidence_transfer (
    transfer_id UUID PRIMARY KEY,
    evidence_id UUID NOT NULL UNIQUE,
    manifest_sha256 CHAR(64) NOT NULL,
    actor_fingerprint CHAR(64) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT membership_evidence_transfer_manifest_valid
        CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT membership_evidence_transfer_actor_valid
        CHECK (actor_fingerprint ~ '^[0-9a-f]{64}$')
);

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION reject_membership_evidence_transfer_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'identity evidence transfer receipts are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_membership_evidence_transfer_no_update_or_delete
BEFORE UPDATE OR DELETE ON membership_identity_evidence_transfer
FOR EACH ROW
EXECUTE FUNCTION reject_membership_evidence_transfer_mutation();

CREATE TRIGGER trg_membership_evidence_transfer_no_truncate
BEFORE TRUNCATE ON membership_identity_evidence_transfer
FOR EACH STATEMENT
EXECUTE FUNCTION reject_membership_evidence_transfer_mutation();
-- [jooq ignore stop]
