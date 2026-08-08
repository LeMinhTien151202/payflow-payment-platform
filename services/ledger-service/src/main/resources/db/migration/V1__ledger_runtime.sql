CREATE SCHEMA IF NOT EXISTS ledger;
CREATE SCHEMA IF NOT EXISTS ledger_runtime;
CREATE TABLE ledger.ledger_accounts (
    id UUID PRIMARY KEY,
    owner_type VARCHAR(30) NOT NULL CHECK (owner_type IN ('MERCHANT','CUSTOMER_ACCOUNT')),
    owner_id UUID NOT NULL,
    currency CHAR(3) NOT NULL CHECK (currency='VND'),
    UNIQUE(owner_type,owner_id,currency)
);
CREATE TABLE ledger.journals (
    id UUID PRIMARY KEY,
    reference_type VARCHAR(30) NOT NULL,
    reference_id UUID NOT NULL,
    journal_type VARCHAR(30) NOT NULL,
    description VARCHAR(500),
    currency CHAR(3) NOT NULL CHECK(currency='VND'),
    status VARCHAR(20) NOT NULL CHECK(status='POSTED'),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(reference_type,reference_id,journal_type),
    CHECK(created_at >= occurred_at)
);
CREATE TABLE ledger.entries (
    id UUID PRIMARY KEY,
    journal_id UUID NOT NULL REFERENCES ledger.journals(id),
    ledger_account_id UUID NOT NULL REFERENCES ledger.ledger_accounts(id),
    direction VARCHAR(10) NOT NULL CHECK(direction IN ('DEBIT','CREDIT')),
    amount NUMERIC(19,4) NOT NULL CHECK(amount>0),
    currency CHAR(3) NOT NULL CHECK(currency='VND')
);
CREATE TABLE ledger.payment_postings (
    payment_id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    journal_id UUID NOT NULL UNIQUE REFERENCES ledger.journals(id),
    amount NUMERIC(19,4) NOT NULL CHECK(amount>0),
    currency CHAR(3) NOT NULL CHECK(currency='VND')
);
CREATE TABLE ledger.refund_postings (
    refund_id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    source_account_id UUID NOT NULL,
    journal_id UUID NOT NULL UNIQUE REFERENCES ledger.journals(id),
    amount NUMERIC(19,4) NOT NULL CHECK(amount>0),
    currency CHAR(3) NOT NULL CHECK(currency='VND')
);
CREATE TABLE ledger_runtime.processed_events (
    event_id UUID NOT NULL,
    consumer_name VARCHAR(100) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(event_id,consumer_name)
);
CREATE TABLE ledger_runtime.outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INTEGER NOT NULL,
    topic VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','PROCESSING','PUBLISHED','FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lock_owner VARCHAR(100),
    lock_until TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_ledger_outbox_pending_due ON ledger_runtime.outbox_events(next_attempt_at,created_at) WHERE status='PENDING';
CREATE INDEX idx_ledger_outbox_expired_lease ON ledger_runtime.outbox_events(lock_until) WHERE status='PROCESSING';
CREATE INDEX idx_ledger_outbox_aggregate_order ON ledger_runtime.outbox_events(aggregate_type,aggregate_id,created_at);
