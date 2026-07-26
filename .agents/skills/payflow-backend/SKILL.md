---
name: payflow-backend
description: Build, review, debug, test, document, and evolve the PayFlow Java/Spring Boot payment platform while preserving monetary invariants, payment/refund state machines, database-per-service ownership, idempotent APIs, Kafka delivery semantics, Transactional Outbox/Inbox, Saga compensation, security, and observability. Use for PayFlow Java code, Maven/dependencies, service or module design, REST/event contracts, JPA/Flyway/PostgreSQL, Kafka/Redis/Keycloak, concurrency, tests, Docker/Kubernetes, CI/CD, ADRs, runbooks, or portfolio evidence in this repository.
---

# PayFlow Backend

Use this workflow for every PayFlow task.

## Establish context

1. Locate the repository root.
2. Read `AGENTS.md`, `.agent/AGENTS.md`, and `.docs/README.md` completely.
3. Read `.docs/PROJECT_OVERVIEW.md`, `.docs/ARCHITECTURE.md`, `.docs/MODULE_MAP.md`, `.docs/DELIVERY_ROADMAP.md`, `.docs/OPEN_DECISIONS.md`, and `.docs/IMPLEMENTATION_STATUS.md`.
4. For architecture, foundation, cross-service workflow, or major review work, read `PAYFLOW_MICROSERVICE_PROJECT_SPEC.md` completely.
5. For a focused task, read the complete relevant spec sections routed by `.docs/README.md`, then read `.docs/TESTING_STRATEGY.md` and related accepted ADRs.
6. Inspect existing code, migrations, contracts, configuration, and tests before proposing or applying changes.

Treat the spec as the primary product and technical contract. Treat current code as implementation evidence, not permission to weaken an invariant.

## Respect the request boundary

- For read, explain, plan, diagnose, or review requests, do not mutate files unless explicitly asked.
- For build/change requests, implement the smallest complete vertical slice allowed by the current roadmap gate.
- Do not add a service, dependency, infrastructure platform, or optional phase merely because it appears in the final target architecture.
- Do not implement behavior blocked by an `OPEN` entry in `.docs/OPEN_DECISIONS.md`; resolve the contract/ADR first.
- Verify dependency existence and Spring compatibility from official sources before changing platform versions. Use BOM-managed versions and record material choices in an ADR.
- If sources conflict on money, state, security, API/event compatibility, or data ownership, surface the conflict before choosing a risky interpretation.

## Define the change before coding

State briefly:

1. Requirement and business invariants.
2. Current phase/gate and owning service or bounded context.
3. Files to create or modify.
4. Local transaction boundary and concurrency strategy.
5. Database migration, index, and constraint impact.
6. REST/event contract impact, including idempotency and versioning.
7. Failure, retry, timeout, compensation, and recovery behavior.
8. Unit, integration, contract, and E2E tests needed.

Use `references/feature-workflow.md` for the full checklist.

## Implement within service boundaries

- Organize packages by feature/domain, then `api`, `application`, `domain`, and `infrastructure` where justified.
- Keep controllers and Kafka listeners thin. Put use-case orchestration and local transactions in application services.
- Put deterministic state transitions and invariants in domain policies/models that run without Spring.
- Hide JPA, Kafka, Redis, HTTP, Keycloak, and vendor types behind infrastructure adapters/ports.
- Return API/event DTOs, never JPA entities.
- Keep shared libraries technical or contract-focused; never share entities, repositories, migrations, or domain services.
- Make every database service use only its own schema and credentials.

## Protect money and state

Read `references/financial-invariants.md` whenever touching amount, currency, balance, reservation, ledger, fee, settlement, refund, payment state, or Saga completion.

- Use `BigDecimal`/`NUMERIC`, explicit currency, scale, and rounding policy.
- Enforce non-negative balances and uniqueness with database constraints in addition to domain checks.
- Use atomic update or locking for reserve and concurrent refund.
- Post balanced journals atomically and correct them with reversal/new journals, never mutation.
- Route all state changes through explicit transition policies.

## Build reliable messaging

- Write business changes and outbox records in one local transaction.
- Make every consumer transactional and idempotent with a durable processed-event/inbox unique key.
- Acknowledge Kafka only after the local transaction commits.
- Key events by aggregate ID, preserve correlation/causation metadata, and version contracts compatibly.
- Assume duplicates can occur. Do not claim PostgreSQL-to-Kafka exactly-once.
- Bound retries, classify retryable failures, route poison events to DLT, and make replay auditable and idempotent.
- Persist Saga state/deadlines; compensate or enter manual review rather than leaving indefinite in-progress state.

## Handle database changes

- Inspect the owner service's migration history, mapping, constraints, indexes, and transaction behavior first.
- Create a new forward Flyway migration; never edit an applied migration or use Hibernate schema mutation.
- Use expand, backfill, switch, then contract for changes to populated structures.
- Test PostgreSQL-specific locking, JSONB, timestamp, constraint, and index behavior on PostgreSQL via Testcontainers.
- Never reset or truncate non-disposable data without explicit authorization.

## Handle API and security

- Require scoped idempotency for payment, refund, and cancel writes and hash canonical payloads.
- Use Bean Validation plus typed business exceptions mapped to extended Problem Details.
- Distinguish authentication (401), authorization (403), not found (404), and conflict/state/idempotency (409).
- Validate JWT at Gateway and service; enforce actor, merchant, and resource ownership independently of request body IDs.
- Keep internal endpoints private and least-privileged.
- Never expose or log tokens, API keys, webhook secrets, credentials, stack traces, or real personal/payment data.
- Sign webhook raw payloads with timestamped HMAC and implement bounded retry without blocking payment processing.

## Add observability with behavior

- Propagate trace, correlation, causation, payment, and event identity across HTTP/Kafka boundaries.
- Add structured logs and domain/reliability metrics for new transitions, retries, compensation, outbox age, lag, DLT, and invariant rejection.
- Keep Actuator exposure private and readiness meaningful.
- Add or update a runbook when introducing an alert, DLT, manual action, replay, or migration risk.

## Verify and hand off

1. Run the smallest relevant tests while iterating.
2. Run the repository Maven Wrapper verification appropriate to all affected modules when feasible.
3. For schema or delivery semantics, run Testcontainers integration and failure tests; mocks alone are insufficient.
4. For API/event changes, compare serialized contracts and compatibility.
5. For governance, skill, or documentation changes, run `scripts/validate-governance.ps1` and the skill-creator validator.
6. Review using `references/verification.md`.
7. Update `.docs/IMPLEMENTATION_STATUS.md` only with actual command/evidence; do not invent success or performance numbers.
8. Report changed files, contract/schema/event effects, commands/results, unresolved risk, and the next permitted roadmap gate.
