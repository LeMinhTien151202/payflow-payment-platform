CREATE SCHEMA IF NOT EXISTS account;
CREATE SCHEMA IF NOT EXISTS account_runtime;

CREATE TABLE account.accounts (
    id UUID PRIMARY KEY,
    currency CHAR(3) NOT NULL CHECK (currency = 'VND'),
    available_balance NUMERIC(19,4) NOT NULL,
    reserved_balance NUMERIC(19,4) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT accounts_balances_non_negative CHECK (available_balance >= 0 AND reserved_balance >= 0)
);
CREATE TABLE account.balance_reservations (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL UNIQUE,
    account_id UUID NOT NULL REFERENCES account.accounts(id),
    amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency = 'VND'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','CAPTURED','RELEASED','EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT reservation_expiry_after_creation CHECK (expires_at > created_at),
    CONSTRAINT reservation_completion_consistent CHECK (
      (status = 'ACTIVE' AND completed_at IS NULL) OR (status <> 'ACTIVE' AND completed_at IS NOT NULL))
);
CREATE INDEX idx_account_reservation_account_status ON account.balance_reservations(account_id,status);
CREATE INDEX idx_account_reservation_active_expiry ON account.balance_reservations(expires_at) WHERE status='ACTIVE';
CREATE TABLE account.refund_credits (
    id UUID PRIMARY KEY,
    refund_id UUID NOT NULL UNIQUE,
    payment_id UUID NOT NULL,
    account_id UUID NOT NULL REFERENCES account.accounts(id),
    journal_id UUID NOT NULL UNIQUE,
    amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency='VND'),
    credited_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE account_runtime.processed_events (
    event_id UUID NOT NULL,
    consumer_name VARCHAR(100) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(event_id,consumer_name)
);
CREATE TABLE account_runtime.outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INTEGER NOT NULL,
    topic VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROCESSING','PUBLISHED','FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lock_owner VARCHAR(100),
    lock_until TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_account_outbox_pending_due ON account_runtime.outbox_events(next_attempt_at,created_at) WHERE status='PENDING';
CREATE INDEX idx_account_outbox_expired_lease ON account_runtime.outbox_events(lock_until) WHERE status='PROCESSING';
CREATE INDEX idx_account_outbox_aggregate_order ON account_runtime.outbox_events(aggregate_type,aggregate_id,created_at);
