CREATE INDEX ix_circulation_loan_member_history
    ON circulation_loan (member_id, requested_at DESC, id DESC);

CREATE INDEX ix_circulation_loan_member_status_history
    ON circulation_loan (member_id, status, requested_at DESC, id DESC);

CREATE INDEX ix_circulation_reservation_member_history
    ON circulation_reservation (member_id, placed_at DESC, id DESC);

CREATE INDEX ix_circulation_reservation_member_status_history
    ON circulation_reservation (member_id, status, placed_at DESC, id DESC);
