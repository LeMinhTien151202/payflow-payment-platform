-- Refund intake API additions. V5 created the financial capacity and lifecycle columns.

ALTER TABLE payment.refunds
    ADD COLUMN reason VARCHAR(500),
    ADD COLUMN requested_by VARCHAR(255);

-- No writer existed before this migration. The backfill still makes rollout safe if a sandbox inserted
-- fixtures directly between V5 and V6.
UPDATE payment.refunds
SET requested_by = 'legacy:unknown'
WHERE requested_by IS NULL;

ALTER TABLE payment.refunds
    ALTER COLUMN requested_by SET NOT NULL,
    ADD CONSTRAINT refunds_requested_by_not_blank CHECK (btrim(requested_by) <> '');

COMMENT ON COLUMN payment.refunds.reason IS
    'Merchant-supplied audit context. Never propagated to Kafka or logs.';
COMMENT ON COLUMN payment.refunds.requested_by IS
    'JWT subject that initiated the original request; retries replay this stored actor rather than replacing it.';
