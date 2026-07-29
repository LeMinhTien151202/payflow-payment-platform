-- ADR-021: durable facts required to resume and validate refund finalization after restart.

ALTER TABLE payment.refunds
    ADD COLUMN ledger_journal_id UUID,
    ADD COLUMN account_credit_id UUID;

-- No application writer could move a Refund past CREATED before this migration. Refuse to invent
-- journal/credit identities if a sandbox inserted such rows directly; operators must reconcile them.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM payment.refunds
        WHERE status IN ('PROCESSING', 'SUCCEEDED')
    ) THEN
        RAISE EXCEPTION
            'V7 requires reconciliation of pre-existing PROCESSING/SUCCEEDED refunds';
    END IF;
END
$$;

ALTER TABLE payment.refunds
    ADD CONSTRAINT refunds_ledger_fact_matches_status CHECK (
        (status IN ('PROCESSING', 'SUCCEEDED')) = (ledger_journal_id IS NOT NULL)
    ),
    ADD CONSTRAINT refunds_credit_fact_matches_success CHECK (
        (status = 'SUCCEEDED') = (account_credit_id IS NOT NULL)
    );

CREATE UNIQUE INDEX uq_refunds_ledger_journal_id
    ON payment.refunds (ledger_journal_id)
    WHERE ledger_journal_id IS NOT NULL;

CREATE UNIQUE INDEX uq_refunds_account_credit_id
    ON payment.refunds (account_credit_id)
    WHERE account_credit_id IS NOT NULL;

COMMENT ON COLUMN payment.refunds.ledger_journal_id IS
    'Immutable Ledger acknowledgement; once present ADR-021 forbids automatic failure/capacity release.';
COMMENT ON COLUMN payment.refunds.account_credit_id IS
    'Immutable Account credit acknowledgement required before refund SUCCEEDED.';
