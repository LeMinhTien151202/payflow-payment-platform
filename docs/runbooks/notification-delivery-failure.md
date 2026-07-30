# Notification delivery failure runbook

Use this runbook when `payflow.notification.delivery{outcome="failed"}`, `lease_exhausted`, or
`lost_lease` increases, or when pending age grows continuously.

## Safe triage

1. Confirm Notification database, Kafka and service health. Do not infer provider success from a
   consumer offset alone.
2. Inspect counts grouped by `status`, `failure_code` and `template_code`. Do not copy `payload` into
   tickets or logs because it can contain business identifiers.
3. For `lost_lease`, compare provider timeout with `PAYFLOW_NOTIFICATION_LEASE`; the lease must be
   longer than the configured provider timeout and the runtime validates this at startup. Any real
   provider adapter must enforce that timeout itself.
4. For `DELIVERY_LEASE_EXHAUSTED`, investigate worker crashes or shutdowns during the external call.
5. Preserve the notification id, source event id, timestamps, attempt count and stable failure code
   as evidence. Never alter the source event identity or business reference.

## Recovery boundary

Phase 1B has no mutation endpoint and treats provider failure as terminal. Do not update rows or
republish events manually: an unaudited retry can duplicate a real external side effect. Resolve the
provider/configuration fault, preserve evidence, and escalate until the Phase 2 audited retry command
defined under OD-010 is implemented. Kafka consumer failures use bounded retry and then the shared
DLT. Preserve the DLT record, but do not replay it until an audited operation under OD-010 exists.
