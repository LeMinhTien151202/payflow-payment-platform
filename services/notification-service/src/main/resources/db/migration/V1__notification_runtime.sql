CREATE SCHEMA IF NOT EXISTS notification;

CREATE TABLE notification.notifications (
    id                       UUID         NOT NULL,
    source_event_id          UUID         NOT NULL,
    source_event_type        VARCHAR(150) NOT NULL,
    aggregate_id             VARCHAR(100) NOT NULL,
    business_reference_type  VARCHAR(40)  NOT NULL,
    business_reference_id    UUID         NOT NULL,
    recipient_type           VARCHAR(40)  NOT NULL,
    recipient_id             VARCHAR(100) NOT NULL,
    channel                  VARCHAR(20)  NOT NULL,
    template_code            VARCHAR(80)  NOT NULL,
    payload                  JSONB        NOT NULL,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempt_count            INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at          TIMESTAMPTZ  NOT NULL,
    lock_owner               VARCHAR(100),
    lock_until               TIMESTAMPTZ,
    failure_code             VARCHAR(100),
    created_at               TIMESTAMPTZ  NOT NULL,
    last_attempt_at          TIMESTAMPTZ,
    sent_at                  TIMESTAMPTZ,
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT uq_notification_source_channel UNIQUE (source_event_id, channel),
    CONSTRAINT uq_notification_business_channel
        UNIQUE (business_reference_type, business_reference_id, channel),
    CONSTRAINT notification_channel_known CHECK (channel = 'EMAIL'),
    CONSTRAINT notification_status_known
        CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED')),
    CONSTRAINT notification_attempt_non_negative CHECK (attempt_count >= 0),
    CONSTRAINT notification_terminal_consistent CHECK (
        (status = 'SENT' AND sent_at IS NOT NULL AND failure_code IS NULL
            AND lock_owner IS NULL AND lock_until IS NULL)
        OR (status = 'FAILED' AND sent_at IS NULL AND failure_code IS NOT NULL
            AND lock_owner IS NULL AND lock_until IS NULL)
        OR (status = 'PENDING' AND sent_at IS NULL AND failure_code IS NULL
            AND lock_owner IS NULL AND lock_until IS NULL)
        OR (status = 'PROCESSING' AND sent_at IS NULL AND failure_code IS NULL
            AND lock_owner IS NOT NULL AND lock_until IS NOT NULL)
    )
);

CREATE INDEX idx_notifications_pending_due
    ON notification.notifications (next_attempt_at, created_at) WHERE status = 'PENDING';
CREATE INDEX idx_notifications_expired_lease
    ON notification.notifications (lock_until) WHERE status = 'PROCESSING';

CREATE TABLE notification.processed_events (
    event_id       UUID         NOT NULL,
    consumer_name  VARCHAR(100) NOT NULL,
    event_type     VARCHAR(150) NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_notification_processed_events PRIMARY KEY (event_id, consumer_name)
);
