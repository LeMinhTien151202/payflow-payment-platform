# PayFlow — Claude Code entry point

This file exists only to route. It intentionally contains no rules of its own, so
there is exactly one source of truth per topic.

Before analyzing, planning, reviewing, or changing this repository, read
[`.agent/AGENTS.md`](.agent/AGENTS.md) completely and follow it as the canonical
project rule set. Then read [`.docs/README.md`](.docs/README.md) for the
documentation index and the source-of-truth precedence order.

For any PayFlow implementation, architecture, database, Kafka, security, testing,
observability, or delivery work, invoke the `payflow-backend` skill
(`.claude/skills/payflow-backend/SKILL.md`), which routes to the full workflow in
[`.agents/skills/payflow-backend/SKILL.md`](.agents/skills/payflow-backend/SKILL.md).

## Two hard gates

- `.docs/OPEN_DECISIONS.md` — an `OPEN` entry blocks implementation of its listed
  scope until resolved by an accepted ADR. There are currently 10 open entries.
- `.docs/DELIVERY_ROADMAP.md` — determines the only vertical slice permitted next.
  Do not jump ahead to a later phase.

## Repository layout note

`.docs/` is agent-facing guidance during construction. `docs/` is the delivered
product/portfolio documentation. Once implementation starts, do not use `.docs/`
in place of OpenAPI, AsyncAPI/event schema, runbooks, or formal ADRs in `docs/`.
