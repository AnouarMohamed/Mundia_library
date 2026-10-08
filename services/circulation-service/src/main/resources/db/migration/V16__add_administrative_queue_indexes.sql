CREATE INDEX ix_circulation_loan_admin_queue
ON circulation_loan (status, requested_at DESC, id DESC);

CREATE INDEX ix_circulation_reservation_admin_queue
ON circulation_reservation (status, placed_at DESC, id DESC);
