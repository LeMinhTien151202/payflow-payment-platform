-- ADR-019/ADR-020: immutable fee facts and atomic refundable capacity foundation.

ALTER TABLE merchant.merchants
    ADD COLUMN fee_policy_version VARCHAR(100),
    ADD COLUMN fee_rate NUMERIC(8,6),
    ADD COLUMN fee_rounding_mode VARCHAR(20);

-- Payments accepted before fee support never posted a fee. Preserve that historical fact explicitly.
UPDATE merchant.merchants
SET fee_policy_version = 'LEGACY_NO_FEE_V1',
    fee_rate = 0.000000,
    fee_rounding_mode = 'HALF_UP';

ALTER TABLE merchant.merchants
    ALTER COLUMN fee_policy_version SET DEFAULT 'STANDARD_V1',
    ALTER COLUMN fee_rate SET DEFAULT 0.020000,
    ALTER COLUMN fee_rounding_mode SET DEFAULT 'HALF_UP',
    ALTER COLUMN fee_policy_version SET NOT NULL,
    ALTER COLUMN fee_rate SET NOT NULL,
    ALTER COLUMN fee_rounding_mode SET NOT NULL,
    ADD CONSTRAINT merchants_fee_rate_valid CHECK (fee_rate >= 0 AND fee_rate <= 1),
    ADD CONSTRAINT merchants_fee_rounding_known CHECK (fee_rounding_mode = 'HALF_UP'),
    ADD CONSTRAINT merchants_fee_policy_version_not_blank CHECK (btrim(fee_policy_version) <> '');

ALTER TABLE payment.payments
    ADD COLUMN fee_policy_version VARCHAR(100),
    ADD COLUMN applied_fee_rate NUMERIC(8,6),
    ADD COLUMN fee_amount NUMERIC(19,4),
    ADD COLUMN fee_currency CHAR(3),
    ADD COLUMN fee_rounding_mode VARCHAR(20),
    ADD COLUMN total_refunded_amount NUMERIC(19,4),
    ADD COLUMN reserved_refund_amount NUMERIC(19,4),
    ADD COLUMN total_fee_reversed_amount NUMERIC(19,4);

UPDATE payment.payments
SET fee_policy_version = 'LEGACY_NO_FEE_V1',
    applied_fee_rate = 0.000000,
    fee_amount = 0.0000,
    fee_currency = currency,
    fee_rounding_mode = 'HALF_UP',
    total_refunded_amount = 0.0000,
    reserved_refund_amount = 0.0000,
    total_fee_reversed_amount = 0.0000;

ALTER TABLE payment.payments
    ALTER COLUMN fee_policy_version SET DEFAULT 'LEGACY_NO_FEE_V1',
    ALTER COLUMN applied_fee_rate SET DEFAULT 0.000000,
    ALTER COLUMN fee_amount SET DEFAULT 0.0000,
    ALTER COLUMN fee_currency SET DEFAULT 'VND',
    ALTER COLUMN fee_rounding_mode SET DEFAULT 'HALF_UP',
    ALTER COLUMN total_refunded_amount SET DEFAULT 0.0000,
    ALTER COLUMN reserved_refund_amount SET DEFAULT 0.0000,
    ALTER COLUMN total_fee_reversed_amount SET DEFAULT 0.0000,
    ALTER COLUMN fee_policy_version SET NOT NULL,
    ALTER COLUMN applied_fee_rate SET NOT NULL,
    ALTER COLUMN fee_amount SET NOT NULL,
    ALTER COLUMN fee_currency SET NOT NULL,
    ALTER COLUMN fee_rounding_mode SET NOT NULL,
    ALTER COLUMN total_refunded_amount SET NOT NULL,
    ALTER COLUMN reserved_refund_amount SET NOT NULL,
    ALTER COLUMN total_fee_reversed_amount SET NOT NULL,
    ADD CONSTRAINT payments_applied_fee_rate_valid
        CHECK (applied_fee_rate >= 0 AND applied_fee_rate <= 1),
    ADD CONSTRAINT payments_fee_amount_valid CHECK (fee_amount >= 0 AND fee_amount <= amount),
    ADD CONSTRAINT payments_fee_currency_matches CHECK (fee_currency = currency),
    ADD CONSTRAINT payments_fee_rounding_known CHECK (fee_rounding_mode = 'HALF_UP'),
    ADD CONSTRAINT payments_refund_totals_non_negative
        CHECK (total_refunded_amount >= 0 AND reserved_refund_amount >= 0),
    ADD CONSTRAINT payments_refund_capacity_not_exceeded
        CHECK (total_refunded_amount + reserved_refund_amount <= amount),
    ADD CONSTRAINT payments_fee_reversal_valid
        CHECK (total_fee_reversed_amount >= 0 AND total_fee_reversed_amount <= fee_amount);

-- MVP is VND-only, so the default also keeps direct migration fixtures valid. The application writes the
-- value from the immutable snapshot rather than relying on this default.

CREATE TABLE payment.refunds (
    id                    UUID          NOT NULL,
    payment_id            UUID          NOT NULL,
    merchant_id           UUID          NOT NULL,
    idempotency_key       VARCHAR(100)  NOT NULL,
    amount                NUMERIC(19,4) NOT NULL,
    currency              CHAR(3)       NOT NULL,
    fee_reversal_amount   NUMERIC(19,4),
    status                VARCHAR(20)   NOT NULL,
    failure_code          VARCHAR(100),
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,
    completed_at          TIMESTAMPTZ,
    version               BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_refunds PRIMARY KEY (id),
    CONSTRAINT fk_refunds_payment FOREIGN KEY (payment_id) REFERENCES payment.payments (id),
    CONSTRAINT refunds_amount_positive CHECK (amount > 0),
    CONSTRAINT refunds_currency_supported CHECK (currency = 'VND'),
    CONSTRAINT refunds_status_known CHECK (status IN ('CREATED', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT refunds_updated_not_before_created CHECK (updated_at >= created_at),
    CONSTRAINT refunds_completion_matches_status CHECK (
        (status IN ('SUCCEEDED', 'FAILED')) = (completed_at IS NOT NULL)),
    CONSTRAINT refunds_fee_reversal_matches_success CHECK (
        (status = 'SUCCEEDED' AND fee_reversal_amount IS NOT NULL AND fee_reversal_amount >= 0)
        OR (status <> 'SUCCEEDED' AND fee_reversal_amount IS NULL)),
    CONSTRAINT refunds_failure_code_matches_status CHECK (
        (status = 'FAILED') = (failure_code IS NOT NULL))
);

CREATE UNIQUE INDEX uq_refunds_payment_idempotency_key
    ON payment.refunds (payment_id, idempotency_key);
CREATE INDEX idx_refunds_payment_created_at
    ON payment.refunds (payment_id, created_at);
CREATE INDEX idx_refunds_status_updated_at
    ON payment.refunds (status, updated_at);

COMMENT ON COLUMN payment.payments.reserved_refund_amount IS
    'Capacity held by CREATED/PROCESSING refunds. Mutated while holding the payment row FOR UPDATE.';
COMMENT ON COLUMN payment.payments.total_refunded_amount IS
    'Only SUCCEEDED refund principal; never includes in-flight work.';
COMMENT ON COLUMN payment.payments.total_fee_reversed_amount IS
    'Cumulative succeeded fee reversal allocated by ADR-019.';
