CREATE OR REPLACE FUNCTION ledger.reject_posted_journal_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'posted ledger records are immutable';
END
$$;

CREATE TRIGGER trg_journals_immutable
BEFORE UPDATE OR DELETE ON ledger.journals
FOR EACH ROW EXECUTE FUNCTION ledger.reject_posted_journal_mutation();

CREATE TRIGGER trg_entries_immutable
BEFORE UPDATE OR DELETE ON ledger.entries
FOR EACH ROW EXECUTE FUNCTION ledger.reject_posted_journal_mutation();

CREATE TRIGGER trg_payment_postings_immutable
BEFORE UPDATE OR DELETE ON ledger.payment_postings
FOR EACH ROW EXECUTE FUNCTION ledger.reject_posted_journal_mutation();

CREATE TRIGGER trg_refund_postings_immutable
BEFORE UPDATE OR DELETE ON ledger.refund_postings
FOR EACH ROW EXECUTE FUNCTION ledger.reject_posted_journal_mutation();
