CREATE SCHEMA IF NOT EXISTS merchant;
CREATE TABLE merchant.merchants(
 id UUID PRIMARY KEY, code VARCHAR(50) NOT NULL UNIQUE, name VARCHAR(200) NOT NULL,
 status VARCHAR(30) NOT NULL CHECK(status IN('PENDING','ACTIVE','SUSPENDED','CLOSED')),
 default_currency CHAR(3) NOT NULL CHECK(default_currency='VND'),
 fee_rate NUMERIC(8,6) NOT NULL CHECK(fee_rate>=0 AND fee_rate<=1),
 max_transaction_amount NUMERIC(19,4) NOT NULL CHECK(max_transaction_amount>0),
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE merchant.members(
 id UUID PRIMARY KEY, merchant_id UUID NOT NULL REFERENCES merchant.merchants(id),
 user_id VARCHAR(100) NOT NULL, role VARCHAR(50) NOT NULL, status VARCHAR(30) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, UNIQUE(merchant_id,user_id)
);
CREATE TABLE merchant.api_keys(
 id UUID PRIMARY KEY, merchant_id UUID NOT NULL REFERENCES merchant.merchants(id),
 key_prefix VARCHAR(20) NOT NULL UNIQUE, key_hash VARCHAR(100) NOT NULL,
 status VARCHAR(30) NOT NULL CHECK(status IN('ACTIVE','REVOKED','EXPIRED')),
 expires_at TIMESTAMPTZ NOT NULL, last_used_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ,
 CHECK((status='REVOKED' AND revoked_at IS NOT NULL) OR (status<>'REVOKED' AND revoked_at IS NULL))
);
CREATE INDEX idx_merchant_api_key_merchant_status ON merchant.api_keys(merchant_id,status);
CREATE TABLE merchant.webhooks(
 id UUID PRIMARY KEY, merchant_id UUID NOT NULL UNIQUE REFERENCES merchant.merchants(id),
 url VARCHAR(1000) NOT NULL, encrypted_secret TEXT NOT NULL, subscribed_events JSONB NOT NULL,
 enabled BOOLEAN NOT NULL, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE merchant.audit_records(
 id UUID PRIMARY KEY, actor_id VARCHAR(100) NOT NULL, action VARCHAR(100) NOT NULL,
 resource_type VARCHAR(50) NOT NULL, resource_id UUID NOT NULL,
 before_status VARCHAR(50), after_status VARCHAR(50), correlation_id VARCHAR(100) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL
);
CREATE OR REPLACE FUNCTION merchant.reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'merchant audit records are append-only'; END $$;
CREATE TRIGGER trg_merchant_audit_no_update BEFORE UPDATE OR DELETE ON merchant.audit_records
FOR EACH ROW EXECUTE FUNCTION merchant.reject_audit_mutation();
