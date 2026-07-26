---
name: payflow-backend
description: Build, review, debug, test, document, and evolve the PayFlow Java/Spring Boot payment platform while preserving monetary invariants, payment/refund state machines, database-per-service ownership, idempotent APIs, Kafka delivery semantics, Transactional Outbox/Inbox, Saga compensation, security, and observability. Use for PayFlow Java code, Maven/dependencies, service or module design, REST/event contracts, JPA/Flyway/PostgreSQL, Kafka/Redis/Keycloak, concurrency, tests, Docker/Kubernetes, CI/CD, ADRs, runbooks, or portfolio evidence in this repository.
---

# PayFlow Backend

This skill is a router. The canonical rule set lives in the files below — read them
rather than relying on any summary. Do not duplicate their content into this file;
if a rule changes, it changes in the canonical file only.

## Step 1 — Load the governance rules (always)

Read completely, in this order:

1. `AGENTS.md`
2. `.agent/AGENTS.md` — canonical project rule set (13 sections: scope, platform,
   delivery, architecture, financial invariants, messaging, API/idempotency,
   security, database, observability, testing/DoD, agent workflow, reading order)
3. `.docs/README.md` — documentation index and source-of-truth precedence

## Step 2 — Load the delivery context (always)

Read `.docs/PROJECT_OVERVIEW.md`, `.docs/ARCHITECTURE.md`, `.docs/MODULE_MAP.md`,
`.docs/DELIVERY_ROADMAP.md`, `.docs/OPEN_DECISIONS.md`, and
`.docs/IMPLEMENTATION_STATUS.md`.

`.docs/OPEN_DECISIONS.md` is a hard gate: an `OPEN` entry blocks implementation of
its listed scope. Do not pick a side and turn it into an implicit contract.

## Step 3 — Load the full workflow

Read `.agents/skills/payflow-backend/SKILL.md` completely. That file is the
authoritative PayFlow workflow and this router does not replace it. It covers:
establishing context, respecting the request boundary, defining the change before
coding, service boundaries, protecting money and state, reliable messaging,
database changes, API and security, observability, and verification/hand-off.

## Step 4 — Load task-specific references

| Situation | Read |
| --- | --- |
| Any money, payment, account, reservation, ledger, refund, fee, settlement, or Saga-completion change | `.agents/skills/payflow-backend/references/financial-invariants.md` |
| Planning or implementing any feature | `.agents/skills/payflow-backend/references/feature-workflow.md` |
| Before hand-off, review, or claiming completion | `.agents/skills/payflow-backend/references/verification.md` |
| Architecture, foundation, cross-service workflow, or major review | `PAYFLOW_MICROSERVICE_PROJECT_SPEC.md` in full |
| Focused task | The spec sections routed by `.docs/README.md`, plus `.docs/TESTING_STRATEGY.md` |

## Step 5 — Validate governance changes

When changing governance, skill, or documentation files, run:

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/payflow-backend/scripts/validate-governance.ps1
```

## Non-negotiables (enforced by the canonical files, restated only as a guard)

- PostgreSQL only for money. `BigDecimal` in Java, `NUMERIC(19,4)` in PostgreSQL. Never `float`/`double`.
- Business write and outbox insert share one local transaction. Never publish to Kafka directly.
- Consumer writes the processed-event/inbox marker and the business change in one transaction; acknowledge Kafka only after commit.
- Flyway is the schema source of truth; Hibernate uses `ddl-auto=validate`. Never edit an applied migration.
- Testcontainers with real PostgreSQL/Kafka/Redis. Never H2. Never mock a repository to prove locking, constraints, or transactions.
- Never claim end-to-end exactly-once. The target is at-least-once plus idempotent processing.
- Never record throughput, latency, availability, or coverage numbers that were not measured with a reproducible command.
- For read, explain, plan, diagnose, or review requests, do not mutate files.
