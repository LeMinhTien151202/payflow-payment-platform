INSERT INTO merchant.merchants (
    id, code, name, status, default_currency, fee_rate, max_transaction_amount,
    created_at, updated_at, version, fee_policy_version, fee_rounding_mode)
VALUES (
    '11111111-1111-4111-8111-111111111111',
    'MCH-LOCAL-ACTIVE',
    'Local Test Store',
    'ACTIVE',
    'VND',
    0.020000,
    50000000.0000,
    clock_timestamp(),
    clock_timestamp(),
    0,
    'LOCAL_FEE_V1',
    'HALF_UP')
ON CONFLICT (id) DO NOTHING;

-- Local memberships are synchronized after startup by sync-local-merchant-memberships.ps1.
-- Keycloak generates the authoritative user subject; storing a guessed UUID here would
-- make merchant.members disagree with the `sub` claim in the user's real access token.
