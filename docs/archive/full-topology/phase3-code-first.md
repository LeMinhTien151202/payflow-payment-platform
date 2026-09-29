# Phase 3 Docker Compose handoff

Phase 3 keeps Settlement/Reconciliation, event contracts, security and observability. The active
runtime target is Docker Compose. Kubernetes is intentionally deferred and is not required to run
PayFlow.

## Code-only gate

```powershell
.\mvnw.cmd -B -ntp -Pno-docker clean verify
docker compose --env-file .env.example --profile full config --quiet
```

The Compose command only renders and validates configuration; it does not create containers.

## Runtime prerequisites

- Run `.\infrastructure\scripts\prepare-phase3-env.ps1`. It preserves existing values, adds only
  missing Settlement settings and creates `.env.phase2-backup` once.
- For an existing PostgreSQL volume, run `docker exec payflow-postgres bash /docker-entrypoint-initdb.d/02-provision-phase2-databases.sh`. Fresh volumes run `01-create-databases.sh` automatically.
- For an existing Keycloak volume, run .\infrastructure\scripts\provision-phase3-keycloak.ps1$([Environment]::NewLine)  after Keycloak is healthy. The idempotent script adds only missing Phase 3 scopes and client
  assignments; it does not reset the realm, users or existing clients.
- Start the `full` Compose profile so Settlement, PostgreSQL, Kafka consumers and Gateway routing
  run together.

## Docker Compose runtime

```powershell
docker compose --env-file .env --profile full up -d --build
docker compose --env-file .env --profile full ps
```

The profile uses the shared non-root Java image and service-specific database credentials. It does
not invoke `kubectl` or require a Kubernetes cluster.

Retain k6 evidence by copying `performance/reports/TEMPLATE.md`. Never add estimated performance
numbers when a runtime gate has not been executed.

## Deferred evidence

The PostgreSQL fixture test, full-profile Compose smoke and k6 thresholds are not marked passed until
their commands run and their reports are retained. No fabricated latency, throughput, availability
or reconciliation result belongs in the README or CV.

Kubernetes remains an optional future roadmap item. No Kubernetes deployment or operations claim
should be made for the current portfolio scope.