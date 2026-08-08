CREATE TABLE notification.webhook_deliveries(
 id UUID PRIMARY KEY, merchant_id UUID NOT NULL, event_id UUID NOT NULL, event_type VARCHAR(150) NOT NULL,
 raw_body JSONB NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 response_status INTEGER, response_body_excerpt VARCHAR(1000), failure_code VARCHAR(100),
 attempt_count INTEGER NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ NOT NULL,
 lock_owner VARCHAR(100), lock_until TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL,
 delivered_at TIMESTAMPTZ, UNIQUE(merchant_id,event_id),
 CHECK(status IN('PENDING','PROCESSING','DELIVERED','DEAD')), CHECK(attempt_count>=0),
 CHECK((status='PROCESSING' AND lock_owner IS NOT NULL AND lock_until IS NOT NULL)
    OR (status<>'PROCESSING' AND lock_owner IS NULL AND lock_until IS NULL)),
 CHECK((status='DELIVERED' AND delivered_at IS NOT NULL) OR (status<>'DELIVERED' AND delivered_at IS NULL))
);
CREATE INDEX idx_webhook_pending_due ON notification.webhook_deliveries(next_attempt_at,created_at) WHERE status='PENDING';
CREATE INDEX idx_webhook_expired_lease ON notification.webhook_deliveries(lock_until) WHERE status='PROCESSING';
CREATE TABLE notification.webhook_audit(
 id UUID PRIMARY KEY,actor_id VARCHAR(100) NOT NULL,action VARCHAR(100) NOT NULL,
 delivery_id UUID NOT NULL REFERENCES notification.webhook_deliveries(id),correlation_id VARCHAR(100) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL
);
CREATE OR REPLACE FUNCTION notification.reject_webhook_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'webhook audit is append-only'; END $$;
CREATE TRIGGER trg_webhook_audit_no_mutation BEFORE UPDATE OR DELETE ON notification.webhook_audit
FOR EACH ROW EXECUTE FUNCTION notification.reject_webhook_audit_mutation();
