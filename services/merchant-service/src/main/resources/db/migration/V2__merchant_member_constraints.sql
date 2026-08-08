ALTER TABLE merchant.members
    ADD CONSTRAINT merchant_member_role_valid
        CHECK (role IN ('MERCHANT_ADMIN', 'MERCHANT_USER')),
    ADD CONSTRAINT merchant_member_status_valid
        CHECK (status IN ('ACTIVE', 'INACTIVE'));
