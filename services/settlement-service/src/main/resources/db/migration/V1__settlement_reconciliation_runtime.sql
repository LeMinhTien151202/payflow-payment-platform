CREATE SCHEMA IF NOT EXISTS settlement;
CREATE SCHEMA IF NOT EXISTS settlement_runtime;

CREATE TABLE settlement.settlement_batches (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    settlement_date DATE NOT NULL,
    currency CHAR(3) NOT NULL CHECK (currency = 'VND'),
    gross_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (gross_amount >= 0),
    refund_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (refund_amount >= 0),
    fee_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    net_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    transaction_count INTEGER NOT NULL DEFAULT 0 CHECK (transaction_count >= 0),
    refund_count INTEGER NOT NULL DEFAULT 0 CHECK (refund_count >= 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN','CALCULATING','READY','COMPLETED','FAILED')),
    created_at TIMESTAMPTZ NOT NULL,
    calculated_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT settlement_batch_identity UNIQUE (merchant_id, settlement_date, currency),
    CONSTRAINT settlement_batch_net_formula CHECK (
        net_amount = gross_amount - refund_amount - fee_amount),
    CONSTRAINT settlement_batch_completion_consistent CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (status <> 'COMPLETED' AND completed_at IS NULL))
);
CREATE INDEX idx_settlement_batch_merchant_date
    ON settlement.settlement_batches (merchant_id, settlement_date DESC, id);
CREATE INDEX idx_settlement_batch_status_date
    ON settlement.settlement_batches (status, settlement_date);

CREATE TABLE settlement.settlement_items (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES settlement.settlement_batches(id),
    event_id UUID NOT NULL UNIQUE,
    reference_type VARCHAR(20) NOT NULL CHECK (reference_type IN ('PAYMENT','REFUND')),
    reference_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    gross_amount NUMERIC(19,4) NOT NULL CHECK (gross_amount >= 0),
    refund_amount NUMERIC(19,4) NOT NULL CHECK (refund_amount >= 0),
    fee_amount NUMERIC(19,4) NOT NULL,
    net_amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL CHECK (currency = 'VND'),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT settlement_item_business_reference UNIQUE (reference_type, reference_id),
    CONSTRAINT settlement_item_net_formula CHECK (
        net_amount = gross_amount - refund_amount - fee_amount),
    CONSTRAINT settlement_item_shape CHECK (
        (reference_type = 'PAYMENT' AND gross_amount > 0 AND refund_amount = 0 AND fee_amount >= 0)
        OR
        (reference_type = 'REFUND' AND gross_amount = 0 AND refund_amount > 0 AND fee_amount <= 0))
);
CREATE INDEX idx_settlement_item_batch ON settlement.settlement_items(batch_id, occurred_at, id);
CREATE INDEX idx_settlement_item_payment ON settlement.settlement_items(payment_id, reference_type);

CREATE TABLE settlement.financial_facts (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(150) NOT NULL,
    event_version INTEGER NOT NULL CHECK (event_version > 0),
    fact_type VARCHAR(50) NOT NULL CHECK (fact_type IN (
        'PAYMENT_SUCCEEDED','REFUND_SUCCEEDED','LEDGER_PAYMENT_POSTED','LEDGER_REFUND_POSTED',
        'ACCOUNT_FUNDS_CAPTURED','ACCOUNT_REFUND_CREDITED')),
    reference_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    merchant_id UUID,
    amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    fee_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL CHECK (currency = 'VND'),
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT settlement_fact_business_identity UNIQUE (fact_type, reference_id)
);
CREATE INDEX idx_settlement_fact_payment ON settlement.financial_facts(payment_id, fact_type);
CREATE INDEX idx_settlement_fact_occurred ON settlement.financial_facts(occurred_at, fact_type);

CREATE TABLE settlement_runtime.processed_events (
    event_id UUID NOT NULL,
    consumer_name VARCHAR(100) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_id, consumer_name)
);

CREATE TABLE settlement.reconciliation_runs (
    id UUID PRIMARY KEY,
    settlement_date DATE NOT NULL,
    actor_id VARCHAR(255) NOT NULL,
    correlation_id VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    checked_items INTEGER NOT NULL DEFAULT 0 CHECK (checked_items >= 0),
    open_issue_count INTEGER NOT NULL DEFAULT 0 CHECK (open_issue_count >= 0),
    resolved_issue_count INTEGER NOT NULL DEFAULT 0 CHECK (resolved_issue_count >= 0),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);
CREATE INDEX idx_reconciliation_run_date ON settlement.reconciliation_runs(settlement_date, started_at DESC);

CREATE TABLE settlement.reconciliation_issues (
    id UUID PRIMARY KEY,
    issue_key VARCHAR(255) NOT NULL UNIQUE,
    issue_type VARCHAR(80) NOT NULL,
    reference_id UUID NOT NULL,
    payment_id UUID,
    merchant_id UUID,
    settlement_date DATE NOT NULL,
    expected_currency CHAR(3),
    actual_currency CHAR(3),
    expected_amount NUMERIC(19,4),
    actual_amount NUMERIC(19,4),
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN','RESOLVED')),
    first_detected_at TIMESTAMPTZ NOT NULL,
    last_detected_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    last_seen_run_id UUID REFERENCES settlement.reconciliation_runs(id),
    CONSTRAINT reconciliation_resolution_consistent CHECK (
        (status = 'OPEN' AND resolved_at IS NULL)
        OR (status = 'RESOLVED' AND resolved_at IS NOT NULL))
);
CREATE INDEX idx_reconciliation_issue_date_status
    ON settlement.reconciliation_issues(settlement_date, status, last_detected_at DESC);

CREATE TABLE settlement.audit_records (
    id UUID PRIMARY KEY,
    actor_id VARCHAR(255) NOT NULL,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id UUID NOT NULL,
    decision_code VARCHAR(100) NOT NULL,
    before_status VARCHAR(20),
    after_status VARCHAR(20),
    gross_amount NUMERIC(19,4),
    refund_amount NUMERIC(19,4),
    fee_amount NUMERIC(19,4),
    net_amount NUMERIC(19,4),
    correlation_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_settlement_audit_resource ON settlement.audit_records(resource_type, resource_id, created_at);

CREATE TABLE settlement_runtime.outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INTEGER NOT NULL CHECK (event_version > 0),
    topic VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','PROCESSING','PUBLISHED','FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lock_owner VARCHAR(100),
    lock_until TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_settlement_outbox_pending_due
    ON settlement_runtime.outbox_events(next_attempt_at, created_at) WHERE status = 'PENDING';
CREATE INDEX idx_settlement_outbox_expired_lease
    ON settlement_runtime.outbox_events(lock_until) WHERE status = 'PROCESSING';
CREATE INDEX idx_settlement_outbox_aggregate_order
    ON settlement_runtime.outbox_events(aggregate_type, aggregate_id, created_at);

CREATE OR REPLACE FUNCTION settlement.reject_immutable_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'settlement financial facts and audit rows are append-only';
END
$$;

CREATE TRIGGER trg_settlement_items_immutable
    BEFORE UPDATE OR DELETE ON settlement.settlement_items
    FOR EACH ROW EXECUTE FUNCTION settlement.reject_immutable_mutation();
CREATE TRIGGER trg_settlement_facts_immutable
    BEFORE UPDATE OR DELETE ON settlement.financial_facts
    FOR EACH ROW EXECUTE FUNCTION settlement.reject_immutable_mutation();
CREATE TRIGGER trg_settlement_audit_immutable
    BEFORE UPDATE OR DELETE ON settlement.audit_records
    FOR EACH ROW EXECUTE FUNCTION settlement.reject_immutable_mutation();

CREATE OR REPLACE FUNCTION settlement.reject_completed_batch_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status = 'COMPLETED' THEN
        RAISE EXCEPTION 'completed settlement batch is immutable';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_settlement_completed_batch_immutable
    BEFORE UPDATE OR DELETE ON settlement.settlement_batches
    FOR EACH ROW EXECUTE FUNCTION settlement.reject_completed_batch_mutation();
