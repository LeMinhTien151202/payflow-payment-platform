CREATE SCHEMA IF NOT EXISTS reporting;
CREATE TABLE reporting.projection_generations(
 id UUID PRIMARY KEY,status VARCHAR(20) NOT NULL CHECK(status IN('BUILDING','READY','ACTIVE','RETIRED','REJECTED')),
 created_at TIMESTAMPTZ NOT NULL,completed_at TIMESTAMPTZ
);
INSERT INTO reporting.projection_generations(id,status,created_at,completed_at)
VALUES('00000000-0000-4000-8000-000000000001','ACTIVE',clock_timestamp(),clock_timestamp());
CREATE TABLE reporting.active_generation(
 singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK(singleton),generation_id UUID NOT NULL REFERENCES reporting.projection_generations(id),
 activated_at TIMESTAMPTZ NOT NULL
);
INSERT INTO reporting.active_generation(singleton,generation_id,activated_at)
VALUES(true,'00000000-0000-4000-8000-000000000001',clock_timestamp());
CREATE TABLE reporting.event_log(
 event_id UUID PRIMARY KEY,event_type VARCHAR(150) NOT NULL,event_version INTEGER NOT NULL,
 aggregate_id VARCHAR(100) NOT NULL,occurred_at TIMESTAMPTZ NOT NULL,payload JSONB NOT NULL,received_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_reporting_event_replay ON reporting.event_log(occurred_at,event_id);
CREATE TABLE reporting.payment_projection(
 generation_id UUID NOT NULL REFERENCES reporting.projection_generations(id),payment_id UUID NOT NULL,
 merchant_id UUID NOT NULL,customer_id UUID,amount NUMERIC(19,4) NOT NULL CHECK(amount>0),
 currency CHAR(3) NOT NULL CHECK(currency='VND'),status VARCHAR(30) NOT NULL,
 refunded_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK(refunded_amount>=0 AND refunded_amount<=amount),
 created_at TIMESTAMPTZ NOT NULL,completed_at TIMESTAMPTZ,updated_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(generation_id,payment_id)
);
CREATE INDEX idx_reporting_payment_merchant_date ON reporting.payment_projection(generation_id,merchant_id,created_at);
CREATE TABLE reporting.audit_records(
 id UUID PRIMARY KEY,actor_id VARCHAR(100) NOT NULL,action VARCHAR(100) NOT NULL,resource_id UUID NOT NULL,
 before_value VARCHAR(100),after_value VARCHAR(100),correlation_id VARCHAR(100) NOT NULL,created_at TIMESTAMPTZ NOT NULL
);
CREATE OR REPLACE FUNCTION reporting.reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'reporting audit is append-only'; END $$;
CREATE TRIGGER trg_reporting_audit_no_mutation BEFORE UPDATE OR DELETE ON reporting.audit_records
FOR EACH ROW EXECUTE FUNCTION reporting.reject_audit_mutation();
