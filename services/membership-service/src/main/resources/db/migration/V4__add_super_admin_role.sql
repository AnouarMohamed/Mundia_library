ALTER TABLE membership_member
    DROP CONSTRAINT membership_member_role_valid;

ALTER TABLE membership_member
    ADD CONSTRAINT membership_member_role_valid
    CHECK (membership_role IN ('USER', 'ADMIN', 'SUPER_ADMIN'));
