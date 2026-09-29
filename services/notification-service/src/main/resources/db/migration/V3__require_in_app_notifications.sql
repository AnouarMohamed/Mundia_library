ALTER TABLE notification_preference
    ADD CONSTRAINT notification_preference_in_app_required
        CHECK (in_app_enabled);

ALTER TABLE notification_delivery
    ADD CONSTRAINT notification_delivery_in_app_required
        CHECK (channel <> 'IN_APP' OR status = 'DELIVERED'),
    ADD CONSTRAINT notification_delivery_suppression_terminal
        CHECK (
            status <> 'SUPPRESSED'
            OR (
                next_attempt_at IS NULL
                AND delivered_at IS NULL
                AND provider_message_ref IS NULL
            )
        );
