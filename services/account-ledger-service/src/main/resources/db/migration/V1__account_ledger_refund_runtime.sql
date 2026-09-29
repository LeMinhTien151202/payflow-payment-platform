-- ADR-017/021: separate Account, Ledger and operational ownership inside the MVP deployable.

CREATE SCHEMA IF NOT EXISTS account;
CREATE SCHEMA IF NOT EXISTS ledger;
CREATE SCHEMA IF NOT EXISTS account_ledger;

CREATE TABLE account.accounts (
    id                 UUID          NOT NULL,
    currency           CHAR(3)       NOT NULL,
    available_balance  NUMERIC(19,4) NOT NULL,
    reserved_balance   NUMERIC(19,4) NOT NULL,
    status             VARCHAR(20)   NOT NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT accounts_currency_supported CHECK (currency = 'VND'),
    CONSTRAINT accounts_balances_non_negative
        CHECK (available_balance >= 0 AND reserved_balance >= 0),
    CONSTRAINT accounts_status_known CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

CREATE TABLE account.balance_reservations (
    id            UUID          NOT NULL,
    payment_id    UUID          NOT NULL,
    account_id    UUID          NOT NULL,
    amount        NUMERIC(19,4) NOT NULL,
    currency      CHAR(3)       NOT NULL,
    status        VARCHAR(20)   NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL,
    expires_at    TIMESTAMPTZ   NOT NULL,
    completed_at  TIMESTAMPTZ,
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_balance_reservations PRIMARY KEY (id),
    CONSTRAINT uq_balance_reservations_payment UNIQUE (payment_id),
    CONSTRAINT fk_balance_reservations_account
        FOREIGN KEY (account_id) REFERENCES account.accounts (id),
    CONSTRAINT balance_reservations_amount_positive CHECK (amount > 0),
    CONSTRAINT balance_reservations_currency_supported CHECK (currency = 'VND'),
    CONSTRAINT balance_reservations_status_known
        CHECK (status IN ('ACTIVE', 'CAPTURED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT balance_reservations_expiry_after_creation CHECK (expires_at > created_at),
    CONSTRAINT balance_reservations_completion_consistent CHECK (
        (status = 'ACTIVE' AND completed_at IS NULL)
        OR (status <> 'ACTIVE' AND completed_at IS NOT NULL))
);

CREATE INDEX idx_balance_reservations_account_status
    ON account.balance_reservations (account_id, status);

CREATE INDEX idx_balance_reservations_active_expiry
    ON account.balance_reservations (expires_at)
    WHERE status = 'ACTIVE';

CREATE TABLE account.refund_credits (
    id           UUID          NOT NULL,
    refund_id    UUID          NOT NULL,
    payment_id   UUID          NOT NULL,
    account_id   UUID          NOT NULL,
    journal_id   UUID          NOT NULL,
    amount       NUMERIC(19,4) NOT NULL,
    currency     CHAR(3)       NOT NULL,
    credited_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_refund_credits PRIMARY KEY (id),
    CONSTRAINT fk_refund_credits_account
        FOREIGN KEY (account_id) REFERENCES account.accounts (id),
    CONSTRAINT uq_refund_credits_refund UNIQUE (refund_id),
    CONSTRAINT uq_refund_credits_journal UNIQUE (journal_id),
    CONSTRAINT refund_credits_amount_positive CHECK (amount > 0),
    CONSTRAINT refund_credits_currency_supported CHECK (currency = 'VND')
);

CREATE TABLE ledger.ledger_accounts (
    id          UUID        NOT NULL,
    owner_type  VARCHAR(30) NOT NULL,
    owner_id    UUID        NOT NULL,
    currency    CHAR(3)     NOT NULL,
    CONSTRAINT pk_ledger_accounts PRIMARY KEY (id),
    CONSTRAINT uq_ledger_accounts_owner UNIQUE (owner_type, owner_id, currency),
    CONSTRAINT ledger_accounts_owner_type_known
        CHECK (owner_type IN ('MERCHANT', 'CUSTOMER_ACCOUNT')),
    CONSTRAINT ledger_accounts_currency_supported CHECK (currency = 'VND')
);

CREATE TABLE ledger.journals (
    id              UUID         NOT NULL,
    reference_type  VARCHAR(30)  NOT NULL,
    reference_id    UUID         NOT NULL,
    journal_type    VARCHAR(30)  NOT NULL,
    description     VARCHAR(500),
    currency        CHAR(3)      NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_journals PRIMARY KEY (id),
    CONSTRAINT uq_journals_business_reference
        UNIQUE (reference_type, reference_id, journal_type),
    CONSTRAINT journals_refund_currency_supported CHECK (currency = 'VND'),
    CONSTRAINT journals_status_posted CHECK (status = 'POSTED'),
    CONSTRAINT journals_created_not_before_occurred CHECK (created_at >= occurred_at)
);

CREATE TABLE ledger.entries (
    id                 UUID          NOT NULL,
    journal_id         UUID          NOT NULL,
    ledger_account_id  UUID          NOT NULL,
    direction          VARCHAR(10)   NOT NULL,
    amount             NUMERIC(19,4) NOT NULL,
    currency           CHAR(3)       NOT NULL,
    CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
    CONSTRAINT fk_ledger_entries_journal
        FOREIGN KEY (journal_id) REFERENCES ledger.journals (id),
    CONSTRAINT fk_ledger_entries_account
        FOREIGN KEY (ledger_account_id) REFERENCES ledger.ledger_accounts (id),
    CONSTRAINT ledger_entries_direction_known CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entries_amount_positive CHECK (amount > 0),
    CONSTRAINT ledger_entries_currency_supported CHECK (currency = 'VND')
);

CREATE TABLE ledger.payment_postings (
    payment_id   UUID          NOT NULL,
    customer_id  UUID          NOT NULL,
    merchant_id  UUID          NOT NULL,
    journal_id   UUID          NOT NULL,
    amount       NUMERIC(19,4) NOT NULL,
    currency     CHAR(3)       NOT NULL,
    CONSTRAINT pk_payment_postings PRIMARY KEY (payment_id),
    CONSTRAINT uq_payment_postings_journal UNIQUE (journal_id),
    CONSTRAINT fk_payment_postings_journal
        FOREIGN KEY (journal_id) REFERENCES ledger.journals (id),
    CONSTRAINT payment_postings_amount_positive CHECK (amount > 0),
    CONSTRAINT payment_postings_currency_supported CHECK (currency = 'VND')
);

CREATE TABLE ledger.refund_postings (
    refund_id          UUID          NOT NULL,
    payment_id         UUID          NOT NULL,
    merchant_id        UUID          NOT NULL,
    source_account_id  UUID          NOT NULL,
    journal_id         UUID          NOT NULL,
    amount             NUMERIC(19,4) NOT NULL,
    currency           CHAR(3)       NOT NULL,
    CONSTRAINT pk_refund_postings PRIMARY KEY (refund_id),
    CONSTRAINT uq_refund_postings_journal UNIQUE (journal_id),
    CONSTRAINT fk_refund_postings_journal
        FOREIGN KEY (journal_id) REFERENCES ledger.journals (id),
    CONSTRAINT refund_postings_amount_positive CHECK (amount > 0),
    CONSTRAINT refund_postings_currency_supported CHECK (currency = 'VND')
);

CREATE TABLE account_ledger.processed_events (
    event_id       UUID         NOT NULL,
    consumer_name  VARCHAR(100) NOT NULL,
    event_type     VARCHAR(150) NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_account_ledger_processed_events PRIMARY KEY (event_id, consumer_name)
);

CREATE TABLE account_ledger.outbox_events (
    id               UUID         NOT NULL,
    aggregate_type   VARCHAR(100) NOT NULL,
    aggregate_id     VARCHAR(100) NOT NULL,
    event_type       VARCHAR(150) NOT NULL,
    event_version    INTEGER      NOT NULL,
    topic            VARCHAR(255) NOT NULL,
    payload          JSONB        NOT NULL,
    headers          JSONB        NOT NULL,
    status           VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    attempt_count    INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL,
    lock_owner       VARCHAR(100),
    lock_until       TIMESTAMPTZ,
    last_error       VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL,
    published_at     TIMESTAMPTZ,
    CONSTRAINT pk_account_ledger_outbox_events PRIMARY KEY (id),
    CONSTRAINT account_ledger_outbox_status_known
        CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT account_ledger_outbox_attempt_non_negative CHECK (attempt_count >= 0)
);

CREATE INDEX idx_account_ledger_outbox_pending_due
    ON account_ledger.outbox_events (next_attempt_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_account_ledger_outbox_expired_lease
    ON account_ledger.outbox_events (lock_until)
    WHERE status = 'PROCESSING';

CREATE INDEX idx_account_ledger_outbox_aggregate_order
    ON account_ledger.outbox_events (aggregate_type, aggregate_id, created_at);
