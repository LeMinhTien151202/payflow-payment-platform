ALTER TABLE merchant.merchants
    ADD COLUMN fee_policy_version VARCHAR(100) NOT NULL DEFAULT 'FEE_V1',
    ADD COLUMN fee_rounding_mode VARCHAR(30) NOT NULL DEFAULT 'HALF_UP';

ALTER TABLE merchant.merchants
    ADD CONSTRAINT merchant_fee_policy_version_non_blank
        CHECK (length(trim(fee_policy_version)) BETWEEN 1 AND 100),
    ADD CONSTRAINT merchant_fee_rounding_mode_supported
        CHECK (fee_rounding_mode = 'HALF_UP');
