CREATE SCHEMA IF NOT EXISTS risk;

CREATE TABLE risk.risk_assessments (
    id                           UUID          NOT NULL,
    payment_id                   UUID          NOT NULL,
    customer_id                  UUID          NOT NULL,
    merchant_id                  UUID          NOT NULL,
    source_account_id            UUID          NOT NULL,
    amount                       NUMERIC(19,4) NOT NULL,
    currency                     CHAR(3)       NOT NULL,
    payment_created_at           TIMESTAMPTZ   NOT NULL,
    payment_count_last_minute    INTEGER       NOT NULL,
    total_amount_last_hour       NUMERIC(19,4) NOT NULL,
    new_device                   BOOLEAN       NOT NULL,
    failed_payments_last_10m     INTEGER       NOT NULL,
    merchant_suspicious          BOOLEAN       NOT NULL,
    ip_country_changed           BOOLEAN       NOT NULL,
    score                        INTEGER       NOT NULL,
    level                        VARCHAR(20)   NOT NULL,
    decision                     VARCHAR(30)   NOT NULL,
    matched_rules                JSONB         NOT NULL,
    policy_version               VARCHAR(30)   NOT NULL,
    assessed_at                  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_risk_assessments PRIMARY KEY (id),
    CONSTRAINT uq_risk_assessments_payment UNIQUE (payment_id),
    CONSTRAINT risk_assessment_amount_positive CHECK (amount > 0),
    CONSTRAINT risk_assessment_currency_supported CHECK (currency = 'VND'),
    CONSTRAINT risk_assessment_velocity_non_negative
        CHECK (payment_count_last_minute >= 0 AND total_amount_last_hour >= 0
               AND failed_payments_last_10m >= 0),
    CONSTRAINT risk_assessment_score_range CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT risk_assessment_level_known
        CHECK (level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT risk_assessment_decision_known
        CHECK (decision IN ('APPROVED', 'REVIEW_REQUIRED', 'REJECTED'))
);

CREATE INDEX idx_risk_assessments_customer_time
    ON risk.risk_assessments (customer_id, payment_created_at DESC);

CREATE TABLE risk.processed_events (
    event_id       UUID         NOT NULL,
    consumer_name  VARCHAR(100) NOT NULL,
    event_type     VARCHAR(150) NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_risk_processed_events PRIMARY KEY (event_id, consumer_name)
);

CREATE TABLE risk.outbox_events (
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
    CONSTRAINT pk_risk_outbox_events PRIMARY KEY (id),
    CONSTRAINT risk_outbox_status_known
        CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT risk_outbox_attempt_non_negative CHECK (attempt_count >= 0)
);

CREATE INDEX idx_risk_outbox_pending_due
    ON risk.outbox_events (next_attempt_at, created_at) WHERE status = 'PENDING';
CREATE INDEX idx_risk_outbox_expired_lease
    ON risk.outbox_events (lock_until) WHERE status = 'PROCESSING';
CREATE INDEX idx_risk_outbox_aggregate_order
    ON risk.outbox_events (aggregate_type, aggregate_id, created_at);
