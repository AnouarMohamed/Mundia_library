CREATE TABLE membership_legacy_import (
    import_id UUID PRIMARY KEY,
    source_revision CHAR(64) NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL,
    actor_fingerprint CHAR(64) NOT NULL,
    member_count INTEGER NOT NULL,
    quarantined_evidence_count INTEGER NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT membership_legacy_import_hashes_valid CHECK (
        source_revision ~ '^[0-9a-f]{64}$' AND
        manifest_sha256 ~ '^[0-9a-f]{64}$' AND
        actor_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT membership_legacy_import_counts_valid CHECK (
        member_count BETWEEN 1 AND 100 AND
        quarantined_evidence_count = member_count
    )
);

CREATE INDEX membership_legacy_import_completed_index
    ON membership_legacy_import (completed_at DESC, import_id);

CREATE TABLE membership_legacy_evidence_quarantine (
    member_id UUID PRIMARY KEY REFERENCES membership_member(member_id) ON DELETE RESTRICT,
    reference_sha256 CHAR(64) NOT NULL UNIQUE,
    reason VARCHAR(64) NOT NULL,
    quarantined_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT membership_legacy_evidence_reference_valid
        CHECK (reference_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT membership_legacy_evidence_reason_valid
        CHECK (reason = 'UNVERIFIED_LEGACY_REFERENCE')
);
