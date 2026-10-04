CREATE TABLE circulation_loan_notification_reminder (
    loan_id UUID NOT NULL REFERENCES circulation_loan (id),
    due_at TIMESTAMPTZ NOT NULL,
    reminder_type VARCHAR(32) NOT NULL,
    notification_event_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (loan_id, due_at, reminder_type),
    CONSTRAINT uq_circulation_loan_notification_reminder_event
        UNIQUE (notification_event_id),
    CONSTRAINT ck_circulation_loan_notification_reminder_type
        CHECK (reminder_type IN ('DUE_SOON', 'OVERDUE'))
);

CREATE INDEX ix_circulation_loan_notification_reminder_created
    ON circulation_loan_notification_reminder (created_at);

