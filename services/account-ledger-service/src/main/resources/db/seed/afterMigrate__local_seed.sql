-- Fake local-only fixtures for the Phase 1B portfolio scenarios.
--
-- This Flyway callback is loaded only by the `local` Spring profile. Every insert is idempotent and
-- deliberately uses ON CONFLICT DO NOTHING: restarting an application must never replenish money or
-- overwrite a balance that a developer changed. To return to the documented initial state, remove
-- the disposable Compose volumes explicitly and let migrations rebuild them.

-- Source accounts are the operational balance source of truth. The happy-path account starts with
-- 1,000,000 VND; the second account makes the insufficient-funds scenario reproducible.
INSERT INTO account.accounts (
    id, currency, available_balance, reserved_balance, status, version)
VALUES
    ('039bedb6-b2d6-47df-aa25-2035e39136a3', 'VND', 1000000.0000, 0.0000, 'ACTIVE', 0),
    ('55555555-5555-4555-8555-555555555555', 'VND',  100000.0000, 0.0000, 'ACTIVE', 0)
ON CONFLICT (id) DO NOTHING;

-- Ledger mappings are intentionally separate from operational accounts. Payment posting currently
-- identifies the customer side by customerId, while refund posting identifies it by sourceAccountId;
-- both mappings are therefore required for the same local workflow to post and later refund safely.
INSERT INTO ledger.ledger_accounts (id, owner_type, owner_id, currency)
VALUES
    ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'MERCHANT',
     '11111111-1111-4111-8111-111111111111', 'VND'),
    ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'CUSTOMER_ACCOUNT',
     '3beff442-7f10-4504-aab4-12d985cf3e95', 'VND'),
    ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'CUSTOMER_ACCOUNT',
     '039bedb6-b2d6-47df-aa25-2035e39136a3', 'VND'),
    ('dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'CUSTOMER_ACCOUNT',
     '44444444-4444-4444-8444-444444444444', 'VND'),
    ('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', 'CUSTOMER_ACCOUNT',
     '55555555-5555-4555-8555-555555555555', 'VND')
ON CONFLICT (id) DO NOTHING;
