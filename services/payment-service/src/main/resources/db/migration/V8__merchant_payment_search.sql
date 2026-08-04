-- Phase 2: bounded merchant payment search.
--
-- The existing (merchant_id, created_at DESC) index serves unfiltered history. This second index
-- serves the status-filtered shape without replacing or mutating an already-applied migration.
-- The id suffix matches the API's deterministic tie-breaker when timestamps are equal.

CREATE INDEX idx_payments_merchant_status_created_id
    ON payment.payments (merchant_id, status, created_at DESC, id DESC);

COMMENT ON INDEX payment.idx_payments_merchant_status_created_id IS
    'Phase 2 payment search by authenticated merchant and optional status, ordered newest first.';
