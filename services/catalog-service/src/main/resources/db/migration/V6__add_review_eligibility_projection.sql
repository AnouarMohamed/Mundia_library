CREATE TABLE catalog_loan_review_projection (
    loan_id UUID PRIMARY KEY,
    member_id UUID NOT NULL,
    edition_id UUID NOT NULL REFERENCES catalog_edition(edition_id) ON DELETE RESTRICT,
    copy_id UUID,
    status VARCHAR(32) NOT NULL,
    returned_at TIMESTAMPTZ,
    source_version BIGINT NOT NULL,
    source_event_id UUID NOT NULL UNIQUE,
    source_occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT catalog_loan_review_status_valid
        CHECK (status IN ('REQUESTED', 'ACTIVE', 'RETURNED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT catalog_loan_review_returned_valid
        CHECK ((status = 'RETURNED' AND returned_at IS NOT NULL) OR status <> 'RETURNED'),
    CONSTRAINT catalog_loan_review_source_version_valid CHECK (source_version >= 0),
    CONSTRAINT catalog_loan_review_timestamps_valid CHECK (created_at <= updated_at)
);

CREATE INDEX catalog_loan_review_member_edition_index
    ON catalog_loan_review_projection (member_id, edition_id, status, loan_id);
