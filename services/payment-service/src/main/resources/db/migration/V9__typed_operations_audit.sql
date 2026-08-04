-- ADR-022: privileged operations audit accepts only typed, allowlisted snapshots in application code.

CREATE TABLE payment.audit_records (
    audit_id       UUID         NOT NULL,
    action         VARCHAR(100) NOT NULL,
    resource_type  VARCHAR(100) NOT NULL,
    resource_id    UUID         NOT NULL,
    actor_subject  VARCHAR(255) NOT NULL,
    decision_code  VARCHAR(100) NOT NULL,
    correlation_id VARCHAR(64)  NOT NULL,
    before_data    JSONB        NOT NULL,
    after_data     JSONB        NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_audit_records PRIMARY KEY (audit_id),
    CONSTRAINT audit_action_stable CHECK (action ~ '^[A-Z][A-Z0-9_]{0,99}$'),
    CONSTRAINT audit_resource_type_stable CHECK (resource_type ~ '^[A-Z][A-Z0-9_]{0,99}$'),
    CONSTRAINT audit_decision_stable CHECK (decision_code ~ '^[A-Z][A-Z0-9_]{0,99}$'),
    CONSTRAINT audit_actor_not_blank CHECK (btrim(actor_subject) <> ''),
    CONSTRAINT audit_before_is_object CHECK (jsonb_typeof(before_data) = 'object'),
    CONSTRAINT audit_after_is_object CHECK (jsonb_typeof(after_data) = 'object')
);

CREATE INDEX idx_audit_records_resource_time
    ON payment.audit_records (resource_type, resource_id, occurred_at DESC);
CREATE INDEX idx_audit_records_retention
    ON payment.audit_records (occurred_at);

CREATE FUNCTION payment.reject_audit_record_mutation() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'payment.audit_records is append-only; % is not permitted', TG_OP;
END;
$$;

CREATE TRIGGER trg_audit_records_append_only
    BEFORE UPDATE OR DELETE ON payment.audit_records
    FOR EACH ROW EXECUTE FUNCTION payment.reject_audit_record_mutation();

COMMENT ON TABLE payment.audit_records IS
    'ADR-022 typed operations evidence. Runtime retention is 365 days; purge is external maintenance.';
COMMENT ON COLUMN payment.audit_records.before_data IS
    'Typed allowlisted non-secret facts only; never request/JWT/header/free-form content.';
