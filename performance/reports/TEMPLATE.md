# PayFlow performance evidence template

Copy this file for every retained run. Never replace unknown values with estimates.

## Run identity

- Date/time (UTC):
- Commit SHA:
- Tester:
- Script: `performance/k6/payment-read.js`
- k6 version:
- Raw result artifact:

## Environment

- Target environment and base URL:
- Service image SHA/digest:
- Service replica count and container CPU/memory limits:
- PostgreSQL/Kafka topology:
- Dataset size and payment fixture:
- Load-generator CPU/memory/network:

## Workload

- Scenario:
- Target RPS:
- Duration:
- Virtual-user ceiling:
- Authentication/token source:

## Result

- Requests:
- Throughput:
- Error rate:
- Latency p50/p95/p99:
- Threshold result:
- Saturated resource, if any:

## Observability correlation

- Grafana dashboard time range:
- Trace or correlation IDs:
- Relevant alerts:
- PostgreSQL/Kafka evidence:

## Conclusion

- Pass/fail:
- Bottleneck:
- Follow-up change:
- Comparison with previous retained run:
