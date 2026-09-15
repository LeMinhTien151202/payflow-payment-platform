-- Merchant cancellation closes a Saga only while it is still waiting for Risk.
ALTER TABLE payment.payment_sagas
    DROP CONSTRAINT payment_sagas_status_known;

ALTER TABLE payment.payment_sagas
    ADD CONSTRAINT payment_sagas_status_known CHECK (status IN (
        'RUNNING', 'COMPLETED', 'COMPENSATING', 'COMPENSATED', 'FAILED',
        'CANCELLED', 'MANUAL_REVIEW_REQUIRED'));
