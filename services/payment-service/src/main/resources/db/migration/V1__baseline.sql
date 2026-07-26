-- PayFlow payment-service schema baseline.
--
-- Phase 0 establishes ownership and the migration mechanism only. The Phase 0 gate in
-- DELIVERY_ROADMAP.md forbids business payment structures at this point, so no payment,
-- idempotency, outbox, or Saga table is created here. Those arrive in Phase 1A together with the
-- constraints and indexes that protect their invariants.
--
-- Flyway creates the schema itself (spring.flyway.create-schemas). This migration records who owns
-- it, so a later reader does not have to infer ownership from configuration.

COMMENT ON SCHEMA payment IS
    'Owned exclusively by payment-service. No other service may read or write these tables; '
    'cross-context data moves through REST contracts or Kafka events only.';
