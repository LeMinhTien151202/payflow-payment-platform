# ADR-022: Typed allowlisted audit records

## Status

Accepted — 2026-08-04

## Context

Phase 2 needs privileged manual-review and replay operations. A generic `before_data`/`after_data`
JSONB writer would also be a convenient path for tokens, API keys, webhook secrets, credential hashes
or merchant/customer PII to enter a long-lived audit table. Redaction after persistence is too late.

## Decision

1. Application code never accepts arbitrary maps or arbitrary JSON for an audit record. Every audited
   operation has a typed change object whose serializer emits an explicit field allowlist.
2. The common envelope contains only `audit_id`, action, resource type/id, actor subject, decision
   code, correlation id, occurred time and the typed before/after facts. Request bodies, headers,
   JWTs, email, IP address, names and free-form comments are forbidden.
3. Actor subject is the stable Keycloak `sub`, bounded to 255 characters. It is an identity reference,
   not a copied user profile. Logs and API errors never echo it.
4. PostgreSQL rejects UPDATE and DELETE through an append-only trigger. The application database role
   has INSERT and audited read only; it does not own a generic purge endpoint.
5. Audit reads require `operations:audit:read` and are deny-by-default. Merchant scopes cannot read
   operations audit data. Mutation scopes do not imply audit-read permission.
6. Retention is 365 days. Purge is a separately authenticated database maintenance task that records
   cutoff, row count and operator/change-ticket outside this table before deleting partitions/rows.
   Runtime application code cannot purge audit records.
7. Tests must prove allowlisted serialization, secret-like input cannot be represented, append-only DB
   enforcement, merchant denial and operations-scope access before an operations endpoint ships.

## Consequences

- Adding an audited field requires a reviewed type/schema change instead of silently adding a JSON key.
- Free-form analyst notes belong in a separately classified case-management system, not this audit log.
- Audit rows are useful for accountability but are not the financial source of truth; Payment Saga,
  Account reservation and immutable Ledger journal remain authoritative.

## Rejected alternatives

- Generic JSON plus key-name redaction: nested/renamed secrets bypass it and redaction cannot classify PII.
- Encrypt every snapshot: encryption protects storage but does not justify collecting unnecessary data.
- Application DELETE endpoint: expands compromise impact and undermines append-only evidence.

## Verification gate

- Typed serializer snapshot tests contain only the documented keys.
- Tests search serialized audit fixtures for token/secret/password/api-key material.
- PostgreSQL Testcontainers proves UPDATE and DELETE fail while INSERT succeeds.
- Security slice proves merchant/read scopes receive 403 and `operations:audit:read` is required.
