# Phase 3 code-first handoff

Phase 3 source artifacts can be compiled and unit-tested without infrastructure. Runtime evidence is
intentionally deferred until PostgreSQL, Kafka, Keycloak and Kubernetes are explicitly started.

## Code-only gate

```powershell
.\mvnw.cmd -B -ntp -Pno-docker clean verify
kubectl kustomize infrastructure/k8s/overlays/local
docker compose --env-file .env.example --profile full config --quiet
```

The last command renders Compose only; it does not create containers. `kubectl kustomize` renders
manifests only; it does not contact a cluster.

## Runtime prerequisites for a later session

- Add the three `PAYFLOW_SETTLEMENT_DB_*` variables and settlement runtime variables from
  `.env.example` to the local ignored `.env`.
- Existing PostgreSQL volumes need the idempotent provisioning helper; new volumes use
  `01-create-databases.sh` automatically.
- Re-import or update the local Keycloak realm so tokens contain `settlement:*` and
  `reconciliation:*` scopes.
- Supply Kubernetes Secret `settlement-service-runtime` outside Git with datasource credentials,
  Kafka bootstrap servers, issuer/JWK URLs and optionally OTLP endpoint.
- Replace `replace-with-git-sha` in the staging image reference. Never deploy a floating `latest` tag.

## Staging delivery skeleton

`.github/workflows/staging-settlement.yml` is manual-only, accepts only the `main` branch and deploys
an image tagged with the exact commit SHA. Create the protected GitHub Environment `staging`, add the base64-encoded kubeconfig as
`KUBE_CONFIG_STAGING_B64`, and provision `settlement-service-runtime` in namespace `payflow` before
dispatch. The workflow does not create or store runtime secrets in Git. It validates prerequisites,
applies the rendered base, waits for rollout, checks readiness and attempts rollback on rollout
failure.

Retain k6 evidence by copying `performance/reports/TEMPLATE.md`; do not add invented performance
numbers when a runtime gate has not been executed.

## Deferred evidence

The PostgreSQL fixture test, Compose smoke, Kubernetes rollout/rollback, k6 thresholds and chaos pod
restart are not marked passed until their commands are run and reports are retained. No fabricated
latency, throughput, availability or reconciliation result belongs in the README or CV.
