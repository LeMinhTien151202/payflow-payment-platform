-- Pre-Phase-2 failure recovery, ADR-012 and ADR-018.
-- Expand-only migration: existing payment rows remain valid and no value is rewritten.

ALTER TABLE payment.payments
    DROP CONSTRAINT payments_status_known;

ALTER TABLE payment.payments
    ADD CONSTRAINT payments_status_known CHECK (status IN (
        'CREATED', 'RISK_CHECKING', 'RISK_REJECTED', 'RESERVING_FUNDS', 'PROCESSING',
        'MANUAL_REVIEW_REQUIRED', 'SUCCEEDED', 'FAILED', 'CANCELLED',
        'PARTIALLY_REFUNDED', 'REFUNDED'));

ALTER TABLE payment.payment_status_history
    DROP CONSTRAINT payment_status_history_to_status_known;

ALTER TABLE payment.payment_status_history
    ADD CONSTRAINT payment_status_history_to_status_known CHECK (to_status IN (
        'CREATED', 'RISK_CHECKING', 'RISK_REJECTED', 'RESERVING_FUNDS', 'PROCESSING',
        'MANUAL_REVIEW_REQUIRED', 'SUCCEEDED', 'FAILED', 'CANCELLED',
        'PARTIALLY_REFUNDED', 'REFUNDED'));

-- V2 constrained only to_status. Add the symmetric guard now because resolution can leave
-- MANUAL_REVIEW_REQUIRED and history must reject a fabricated previous state as well.
ALTER TABLE payment.payment_status_history
    ADD CONSTRAINT payment_status_history_from_status_known CHECK (
        from_status IS NULL OR from_status IN (
            'CREATED', 'RISK_CHECKING', 'RISK_REJECTED', 'RESERVING_FUNDS', 'PROCESSING',
            'MANUAL_REVIEW_REQUIRED', 'SUCCEEDED', 'FAILED', 'CANCELLED',
            'PARTIALLY_REFUNDED', 'REFUNDED')) NOT VALID;

ALTER TABLE payment.payment_status_history
    VALIDATE CONSTRAINT payment_status_history_from_status_known;

CREATE TABLE payment.payment_sagas (
    id               UUID         NOT NULL,
    payment_id       UUID         NOT NULL,
    current_step     VARCHAR(50)  NOT NULL,
    status           VARCHAR(30)  NOT NULL,
    deadline_at      TIMESTAMPTZ  NOT NULL,
    retry_count      INTEGER      NOT NULL DEFAULT 0,
    last_error_code  VARCHAR(100),
    reservation_id   UUID,
    journal_id       UUID,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_payment_sagas PRIMARY KEY (id),
    CONSTRAINT fk_payment_sagas_payment
        FOREIGN KEY (payment_id) REFERENCES payment.payments (id),
    CONSTRAINT uq_payment_sagas_payment UNIQUE (payment_id),
    CONSTRAINT payment_sagas_step_known CHECK (current_step IN (
        'RISK_ASSESSMENT', 'RESERVE_FUNDS', 'POST_LEDGER', 'CAPTURE_FUNDS',
        'RELEASE_FUNDS', 'COMPLETED')),
    CONSTRAINT payment_sagas_status_known CHECK (status IN (
        'RUNNING', 'COMPLETED', 'COMPENSATING', 'COMPENSATED', 'FAILED',
        'MANUAL_REVIEW_REQUIRED')),
    CONSTRAINT payment_sagas_retry_not_negative CHECK (retry_count >= 0),
    CONSTRAINT payment_sagas_updated_not_before_created CHECK (updated_at >= created_at),
    CONSTRAINT payment_sagas_reservation_fact_required CHECK (
        current_step NOT IN ('POST_LEDGER', 'CAPTURE_FUNDS', 'RELEASE_FUNDS', 'COMPLETED')
        OR reservation_id IS NOT NULL),
    CONSTRAINT payment_sagas_journal_fact_required CHECK (
        current_step NOT IN ('CAPTURE_FUNDS', 'COMPLETED') OR journal_id IS NOT NULL),
    CONSTRAINT payment_sagas_no_release_after_journal CHECK (
        current_step <> 'RELEASE_FUNDS' OR journal_id IS NULL),
    CONSTRAINT payment_sagas_completed_shape CHECK (
        status <> 'COMPLETED' OR current_step = 'COMPLETED'),
    CONSTRAINT payment_sagas_compensating_shape CHECK (
        status <> 'COMPENSATING' OR current_step = 'RELEASE_FUNDS')
);

CREATE INDEX idx_payment_sagas_due
    ON payment.payment_sagas (deadline_at, updated_at)
    WHERE status IN ('RUNNING', 'COMPENSATING');

CREATE INDEX idx_payment_sagas_manual_review
    ON payment.payment_sagas (updated_at)
    WHERE status = 'MANUAL_REVIEW_REQUIRED';

COMMENT ON TABLE payment.payment_sagas IS
    'Durable Payment-owned Saga state. Scheduler claims due rows with optimistic versioning; '
    'network I/O is performed only after the local transaction commits.';

COMMENT ON COLUMN payment.payment_sagas.reservation_id IS
    'Committed Account fact used to decide whether pre-ledger release compensation is safe.';

COMMENT ON COLUMN payment.payment_sagas.journal_id IS
    'Committed Ledger fact. When present, automatic reservation release is forbidden by ADR-011.';
