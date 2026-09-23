CREATE TABLE membership_member (
    member_id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    full_name VARCHAR(200) NOT NULL,
    university_id INTEGER NOT NULL,
    account_status VARCHAR(20) NOT NULL,
    membership_role VARCHAR(20) NOT NULL,
    max_active_loans INTEGER NOT NULL DEFAULT 5,
    current_active_loans INTEGER NOT NULL DEFAULT 0,
    has_unpaid_overdue_fines BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT membership_member_email_not_blank CHECK (btrim(email) <> ''),
    CONSTRAINT membership_member_email_trimmed CHECK (email = btrim(email)),
    CONSTRAINT membership_member_email_normalized CHECK (email = lower(email)),
    CONSTRAINT membership_member_full_name_not_blank CHECK (btrim(full_name) <> ''),
    CONSTRAINT membership_member_university_id_positive CHECK (university_id > 0),
    CONSTRAINT membership_member_status_valid
        CHECK (account_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT membership_member_role_valid
        CHECK (membership_role IN ('USER', 'ADMIN')),
    CONSTRAINT membership_member_max_active_loans_valid
        CHECK (max_active_loans BETWEEN 0 AND 100),
    CONSTRAINT membership_member_current_active_loans_valid
        CHECK (current_active_loans BETWEEN 0 AND 100),
    CONSTRAINT membership_member_active_loan_count_valid
        CHECK (current_active_loans <= max_active_loans),
    CONSTRAINT membership_member_timestamp_order_valid
        CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX membership_member_email_unique
    ON membership_member (email);

CREATE UNIQUE INDEX membership_member_university_id_unique
    ON membership_member (university_id);

CREATE TABLE membership_identity_evidence (
    evidence_id UUID PRIMARY KEY,
    member_id UUID NOT NULL UNIQUE
        REFERENCES membership_member(member_id) ON DELETE RESTRICT,
    object_key VARCHAR(1024) NOT NULL UNIQUE,
    mime_type VARCHAR(100) NOT NULL,
    file_size INTEGER NOT NULL,
    checksum_sha256 CHAR(64) NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT membership_identity_evidence_object_key_not_blank
        CHECK (btrim(object_key) <> ''),
    CONSTRAINT membership_identity_evidence_mime_type_valid
        CHECK (mime_type IN ('image/jpeg', 'image/png', 'application/pdf')),
    CONSTRAINT membership_identity_evidence_file_size_valid
        CHECK (file_size BETWEEN 1 AND 10485760),
    CONSTRAINT membership_identity_evidence_checksum_valid
        CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$')
);
