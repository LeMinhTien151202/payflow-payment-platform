-- Phase 1B consumer inbox, per ADR-017.
--
-- The row is inserted in the same local transaction as the consumer's payment mutation and
-- outcome outbox row. Duplicate delivery is a normal result of at-least-once messaging, so callers
-- use INSERT ... ON CONFLICT DO NOTHING and only apply business logic when one row was inserted.

CREATE TABLE payment.processed_events (
    event_id       UUID         NOT NULL,
    consumer_name  VARCHAR(100) NOT NULL,
    event_type     VARCHAR(150) NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_processed_events PRIMARY KEY (event_id, consumer_name),
    CONSTRAINT processed_events_consumer_not_blank CHECK (btrim(consumer_name) <> ''),
    CONSTRAINT processed_events_type_not_blank CHECK (btrim(event_type) <> ''),
    CONSTRAINT processed_events_aggregate_not_blank CHECK (btrim(aggregate_id) <> '')
);

CREATE INDEX idx_processed_events_consumer_processed_at
    ON payment.processed_events (consumer_name, processed_at);

COMMENT ON TABLE payment.processed_events IS
    'Durable consumer inbox owned by payment-service. A row and its business/outbox effects must '
    'commit in one local transaction; primary-key conflict is handled with DO NOTHING.';

COMMENT ON COLUMN payment.processed_events.event_id IS
    'Original envelope eventId. Producers must reuse it for every retry or republish.';

COMMENT ON COLUMN payment.processed_events.consumer_name IS
    'Stable logical consumer name, not pod/instance identity; participates in the dedupe key.';

