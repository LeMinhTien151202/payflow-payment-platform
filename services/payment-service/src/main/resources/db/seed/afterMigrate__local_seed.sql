-- Local and test seed data. Never loaded by any other profile — see the `local` profile block in
-- application.yml and src/test/resources/application.yml.
--
-- A Flyway afterMigrate callback rather than a versioned migration, for two reasons: it must not
-- consume a version number that production would then be permanently missing, and it runs again after
-- every migration so a newly added table gets its fixtures without a second mechanism.
--
-- Therefore it must be idempotent. Every statement is ON CONFLICT DO NOTHING on a real unique
-- constraint, so running it a hundred times leaves the same rows and never overwrites data a
-- developer changed by hand.
--
-- Everything here is invented. AGENTS.md section 8 requires seed data to be fake: no real merchant,
-- no real person, no credential, no value copied from anywhere that exists.
--
-- The identifiers are fixed rather than generated so that integration tests, the runbook, and manual
-- curl calls all refer to the same merchant. They are documented in docs/runbooks/local-development.md.

INSERT INTO merchant.merchants (
    id, code, name, status, default_currency, max_transaction_amount, created_at, updated_at, version)
VALUES
    -- The everyday merchant: active, generous limit. Used by the happy path.
    ('11111111-1111-4111-8111-111111111111', 'MCH-LOCAL-ACTIVE', 'Local Test Store',
     'ACTIVE', 'VND', 50000000.0000, now(), now(), 0),

    -- Exists, but must not be able to take money. Without a fixture like this, "payment rejected
    -- because the merchant is suspended" is a branch nobody ever exercises by hand.
    ('22222222-2222-4222-8222-222222222222', 'MCH-LOCAL-SUSPENDED', 'Local Suspended Store',
     'SUSPENDED', 'VND', 50000000.0000, now(), now(), 0),

    -- A deliberately tiny ceiling, so the per-payment limit can be tripped with an obviously small
    -- amount instead of a 50-million-dong request.
    ('33333333-3333-4333-8333-333333333333', 'MCH-LOCAL-LOW-LIMIT', 'Local Low Limit Store',
     'ACTIVE', 'VND', 100000.0000, now(), now(), 0)
ON CONFLICT (id) DO NOTHING;

-- Customers and source accounts are intentionally absent.
--
-- Phase 1A's roadmap line says "merchant/customer/account seed", but the tables those rows belong in
-- are owned by account-service, which does not exist yet. Seeding them here would mean creating them
-- in the payment schema, which is precisely the ownership violation MODULE_MAP.md exists to prevent.
-- payments.customer_id and payments.source_account_id are therefore unvalidated in Phase 1A, and any
-- UUID is accepted. Recorded as a known deviation in .docs/IMPLEMENTATION_STATUS.md.
