-- Phase 1A — payment intake.
--
-- Chỉ tạo những gì gate Phase 1A trong DELIVERY_ROADMAP.md thực sự cần: nhận payment một cách
-- idempotent, ghi nhận trạng thái và lịch sử, và đưa 1 event vào outbox trong cùng một
-- transaction. Mọi thứ mà các phase sau cần được cố ý bỏ qua, và mỗi mục bỏ qua được liệt kê
-- ở cuối file này kèm lý do.
--
-- Tiền là NUMERIC(19,4) với currency là NOT NULL CHAR(3), theo docs/adr/ADR-007. Các cột amount
-- tuyệt đối không bao giờ được nullable: một payment có số tiền không xác định thì không phải là payment.
--
-- Các constraint này lặp lại các quy tắc mà Java domain cũng cưỡng chế. Điều đó là cố ý. Domain
-- chặn một request xấu từ bên ngoài; constraint ngăn một bug lưu trữ tiền ở một trạng thái không thể xảy ra,
-- vốn là loại lỗi không thể sửa đơn thuần bằng cách deploy lại.

-- ---------------------------------------------------------------------------------------------
-- merchant — một schema riêng biệt, hoàn toàn cố ý
-- ---------------------------------------------------------------------------------------------
-- Spec 7.3 gọi đây là một "merchant module/service": nó chưa phải service riêng lúc này, nhưng sẽ là như vậy.
-- Việc cấp cho nó 1 schema ngay từ bây giờ có nghĩa là việc chia tách sau này chỉ là thay đổi mã nguồn cộng với việc di chuyển DB, chứ không phải việc gỡ rối
-- các bảng đan xen vào nhau. Không có gì trong schema payment có khóa ngoại trỏ vào nó — xem ghi chú tại
-- payments.merchant_id.

COMMENT ON SCHEMA merchant IS
    'Merchant catalog. Sở hữu bởi merchant module, hiện tại đang chạy bên trong payment-service. '
    'Chỉ có thể truy cập qua cổng merchant catalog port; không bảng payment nào được tham chiếu tới nó bằng khóa ngoại.';

CREATE TABLE merchant.merchants (
    id                      UUID          NOT NULL,
    code                    VARCHAR(50)   NOT NULL,
    name                    VARCHAR(200)  NOT NULL,
    status                  VARCHAR(30)   NOT NULL,
    default_currency        CHAR(3)       NOT NULL,
    max_transaction_amount  NUMERIC(19,4) NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL,
    updated_at              TIMESTAMPTZ   NOT NULL,
    version                 BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_merchants PRIMARY KEY (id),
    CONSTRAINT merchants_status_known
        CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'CLOSED')),
    -- MVP is VND only. Widening this is a migration plus an FX decision, which is exactly the
    -- conversation a silent second currency in the data would let us skip.
    CONSTRAINT merchants_currency_supported CHECK (default_currency = 'VND'),
    CONSTRAINT merchants_limit_positive CHECK (max_transaction_amount > 0)
);

CREATE UNIQUE INDEX uq_merchants_code ON merchant.merchants (code);

COMMENT ON COLUMN merchant.merchants.max_transaction_amount IS
    'Per-payment ceiling enforced at intake (spec 7.4). Not a cumulative or daily limit.';

-- ---------------------------------------------------------------------------------------------
-- payments
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payment.payments (
    id                  UUID          NOT NULL,
    merchant_id         UUID          NOT NULL,
    customer_id         UUID          NOT NULL,
    source_account_id   UUID          NOT NULL,
    merchant_reference  VARCHAR(100)  NOT NULL,
    idempotency_key     VARCHAR(100)  NOT NULL,
    amount              NUMERIC(19,4) NOT NULL,
    currency            CHAR(3)       NOT NULL,
    status              VARCHAR(40)   NOT NULL,
    description         VARCHAR(500),
    metadata            JSONB,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT payments_amount_positive CHECK (amount > 0),
    CONSTRAINT payments_currency_supported CHECK (currency = 'VND'),
    -- The full state list from spec 7.4, not only the states Phase 1A can reach. The set is fixed by
    -- the spec, so allowing all ten costs nothing and avoids a migration per phase; the domain state
    -- machine is what decides which transitions are legal.
    CONSTRAINT payments_status_known CHECK (status IN (
        'CREATED', 'RISK_CHECKING', 'RISK_REJECTED', 'RESERVING_FUNDS', 'PROCESSING',
        'SUCCEEDED', 'FAILED', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REFUNDED')),
    CONSTRAINT payments_updated_not_before_created CHECK (updated_at >= created_at),
    CONSTRAINT payments_metadata_is_object
        CHECK (metadata IS NULL OR jsonb_typeof(metadata) = 'object')
);

-- Spec 15.4 index list.
CREATE UNIQUE INDEX uq_payments_merchant_reference
    ON payment.payments (merchant_id, merchant_reference);
CREATE UNIQUE INDEX uq_payments_merchant_idempotency_key
    ON payment.payments (merchant_id, idempotency_key);
CREATE INDEX idx_payments_merchant_created_at
    ON payment.payments (merchant_id, created_at DESC);
CREATE INDEX idx_payments_customer_created_at
    ON payment.payments (customer_id, created_at DESC);
CREATE INDEX idx_payments_status_created_at
    ON payment.payments (status, created_at);

COMMENT ON COLUMN payment.payments.merchant_id IS
    'No foreign key to merchant.merchants on purpose: the merchant catalog is destined to become its '
    'own service, and a cross-schema FK would turn that split into a data migration. Existence is '
    'checked through the merchant catalog port at intake.';

COMMENT ON COLUMN payment.payments.source_account_id IS
    'Deviation from spec 7.4, which omits this column while both the create request and the '
    'payment.created event require it. Unvalidated in Phase 1A: account-service does not exist yet.';

COMMENT ON COLUMN payment.payments.idempotency_key IS
    'Kept on the payment as well as in idempotency_records. The unique index here is the hard '
    'guarantee that one key produces at most one payment even if the record table is ever wrong.';

COMMENT ON COLUMN payment.payments.metadata IS
    'Merchant-supplied key/value data, bounded at the API boundary. Never logged: it is opaque '
    'third-party content and may contain data this platform has made no promise about.';

COMMENT ON COLUMN payment.payments.version IS
    'Optimistic lock. Phase 1A only inserts, but the column exists from the start so Phase 1B state '
    'transitions cannot be written without one.';

-- ---------------------------------------------------------------------------------------------
-- payment_status_history — append-only
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payment.payment_status_history (
    id           UUID         NOT NULL,
    payment_id   UUID         NOT NULL,
    from_status  VARCHAR(40),
    to_status    VARCHAR(40)  NOT NULL,
    reason_code  VARCHAR(100),
    metadata     JSONB,
    occurred_at  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_payment_status_history PRIMARY KEY (id),
    -- Same schema, same owner, same service: here a foreign key is free integrity rather than a
    -- future coupling problem.
    CONSTRAINT fk_payment_status_history_payment
        FOREIGN KEY (payment_id) REFERENCES payment.payments (id),
    CONSTRAINT payment_status_history_to_status_known CHECK (to_status IN (
        'CREATED', 'RISK_CHECKING', 'RISK_REJECTED', 'RESERVING_FUNDS', 'PROCESSING',
        'SUCCEEDED', 'FAILED', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REFUNDED')),
    -- from_status IS NULL marks the creation of the payment; every later row must record real
    -- movement, so a no-op transition is a bug rather than a harmless duplicate.
    CONSTRAINT payment_status_history_is_a_transition
        CHECK (from_status IS NULL OR from_status <> to_status),
    CONSTRAINT payment_status_history_metadata_is_object
        CHECK (metadata IS NULL OR jsonb_typeof(metadata) = 'object')
);

CREATE INDEX idx_payment_status_history_payment
    ON payment.payment_status_history (payment_id, occurred_at);

-- The history is the answer to "how did this payment get here". An UPDATE or DELETE would make that
-- answer unreliable without leaving a trace, so the database refuses both.
CREATE FUNCTION payment.reject_status_history_mutation() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION
        'payment.payment_status_history is append-only; % is not permitted', TG_OP;
END;
$$;

CREATE TRIGGER trg_payment_status_history_append_only
    BEFORE UPDATE OR DELETE ON payment.payment_status_history
    FOR EACH ROW EXECUTE FUNCTION payment.reject_status_history_mutation();

COMMENT ON TRIGGER trg_payment_status_history_append_only ON payment.payment_status_history IS
    'Guards against an application bug, not against an operator: TRUNCATE bypasses row triggers, so '
    'disposable environments can still be reset.';

-- ---------------------------------------------------------------------------------------------
-- idempotency_records
-- ---------------------------------------------------------------------------------------------
-- Written inside the same transaction as the payment it describes, so there is no window where a
-- record exists without its payment or the reverse. That is why there is no IN_PROGRESS state: a
-- half-finished request leaves nothing behind at all.

CREATE TABLE payment.idempotency_records (
    id               UUID         NOT NULL,
    scope            VARCHAR(100) NOT NULL,
    idempotency_key  VARCHAR(100) NOT NULL,
    request_hash     VARCHAR(128) NOT NULL,
    resource_id      UUID,
    response_status  INTEGER,
    response_body    JSONB,
    status           VARCHAR(20)  NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_idempotency_records PRIMARY KEY (id),
    -- FAILED is allowed but unused in Phase 1A: a rejected request does not consume the key, because
    -- forcing a client to mint a new key after fixing a typo buys no safety.
    CONSTRAINT idempotency_records_status_known CHECK (status IN ('COMPLETED', 'FAILED')),
    CONSTRAINT idempotency_records_completed_is_replayable CHECK (
        status <> 'COMPLETED'
        OR (resource_id IS NOT NULL AND response_status IS NOT NULL AND response_body IS NOT NULL)),
    CONSTRAINT idempotency_records_expires_after_creation CHECK (expires_at > created_at)
);

-- The one constraint the whole idempotency contract rests on. Two concurrent requests with the same
-- key serialise here: the second blocks on this index until the first commits, then reads the stored
-- response instead of creating a second payment.
CREATE UNIQUE INDEX uq_idempotency_records_scope_key
    ON payment.idempotency_records (scope, idempotency_key);

CREATE INDEX idx_idempotency_records_expires_at
    ON payment.idempotency_records (expires_at);

COMMENT ON COLUMN payment.idempotency_records.scope IS
    'Namespaces the key so one merchant cannot replay another merchant''s response, and so the same '
    'key is independent per endpoint. Format: <merchantId>:<METHOD> <path>.';

COMMENT ON COLUMN payment.idempotency_records.request_hash IS
    'Hash of the canonicalised request. A matching key with a different hash is a client error '
    '(IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST), not a replay.';

COMMENT ON COLUMN payment.idempotency_records.expires_at IS
    'Retention horizon. Nothing deletes expired rows yet; the cleanup job is out of Phase 1A scope '
    'and the column exists so that job does not need a migration.';

-- ---------------------------------------------------------------------------------------------
-- outbox_events
-- ---------------------------------------------------------------------------------------------
-- Spec 8.6 columns plus the three lease columns from docs/adr/ADR-014. They are here from the start
-- rather than added later, because a PROCESSING row with no owner and no deadline is precisely the
-- stuck-row failure OD-008 was opened about.

CREATE TABLE payment.outbox_events (
    id               UUID         NOT NULL,
    aggregate_type   VARCHAR(100) NOT NULL,
    aggregate_id     VARCHAR(100) NOT NULL,
    event_type       VARCHAR(150) NOT NULL,
    event_version    INTEGER      NOT NULL,
    topic            VARCHAR(255) NOT NULL,
    payload          JSONB        NOT NULL,
    headers          JSONB        NOT NULL,
    status           VARCHAR(30)  NOT NULL,
    attempt_count    INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL,
    lock_owner       VARCHAR(100),
    lock_until       TIMESTAMPTZ,
    last_error       VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL,
    published_at     TIMESTAMPTZ,

    CONSTRAINT pk_outbox_events PRIMARY KEY (id),
    CONSTRAINT outbox_events_status_known
        CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT outbox_events_version_positive CHECK (event_version >= 1),
    CONSTRAINT outbox_events_attempts_not_negative CHECK (attempt_count >= 0),
    -- Biconditionals, not one-way checks: PROCESSING without a lease is an unreclaimable row, and a
    -- lease on a row nobody is publishing would make the reclaim query pick up live work.
    CONSTRAINT outbox_events_lease_matches_status CHECK (
        (status = 'PROCESSING') = (lock_owner IS NOT NULL AND lock_until IS NOT NULL)),
    CONSTRAINT outbox_events_published_at_matches_status CHECK (
        (status = 'PUBLISHED') = (published_at IS NOT NULL)),
    CONSTRAINT outbox_events_payload_is_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT outbox_events_headers_is_object CHECK (jsonb_typeof(headers) = 'object')
);

-- Partial indexes rather than the composite (status, next_attempt_at, created_at) in spec 15.4.
-- PUBLISHED rows will be almost the entire table and appear in neither hot query, so indexing them
-- would only make the index large enough to stop being useful. Recorded as a deliberate deviation in
-- docs/adr/ADR-014.
CREATE INDEX idx_outbox_events_dispatch
    ON payment.outbox_events (next_attempt_at, created_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_events_stale
    ON payment.outbox_events (lock_until) WHERE status = 'PROCESSING';
CREATE INDEX idx_outbox_events_aggregate
    ON payment.outbox_events (aggregate_type, aggregate_id, created_at);

COMMENT ON COLUMN payment.outbox_events.id IS
    'Also the envelope eventId, and therefore the consumer deduplication key. Generated at insert '
    'time and never regenerated: a republish after a crash must reuse it or no inbox can recognise '
    'the duplicate (ADR-014).';

COMMENT ON COLUMN payment.outbox_events.payload IS
    'The complete event envelope as it goes on the wire (spec 8.2), not just the data member. Storing '
    'the finished message means a retry publishes identical bytes, and the columns above are '
    'denormalised copies for indexing and routing.';

COMMENT ON COLUMN payment.outbox_events.topic IS
    'Deviation from spec 8.6. Storing the destination keeps the publisher generic; deriving it from '
    'aggregate_type would put the routing table in a second place that has to stay in sync.';

COMMENT ON COLUMN payment.outbox_events.last_error IS
    'Exception class and truncated message only. Never a payload, a stack trace, or anything that '
    'could carry a secret, and never surfaced in an API response.';

-- ---------------------------------------------------------------------------------------------
-- Deliberately not created here
-- ---------------------------------------------------------------------------------------------
-- payments.risk_level, payments.risk_score      OD-009 OPEN: the published 0-100 range does not
--                                               match the sample rule totals, so a CHECK now would
--                                               settle that decision by accident.
-- payments.failure_code, failure_message        Phase 1B. Their vocabulary is OD-003 (rejection and
--                                               risk event taxonomy), still OPEN.
-- payments.total_refunded_amount                OD-005 OPEN: whether in-flight refunds count toward
--                                               capacity changes what this column means.
-- payments.completed_at                         Phase 1B. Nothing in Phase 1A can complete a payment,
--                                               and an always-null column invites a wrong reading.
-- merchants.fee_rate                            OD-004 OPEN: a mutable rate column is exactly the
--                                               interpretation that decision has to choose between.
-- refunds, webhooks, api keys, audit, processed_events
--                                               Later phases, other slices, or a service that does
--                                               not exist yet. processed_events in particular waits
--                                               for OD-007.
