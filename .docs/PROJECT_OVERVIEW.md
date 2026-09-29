# Tổng quan PayFlow Core

## Mục tiêu

PayFlow Core là một vertical slice payment đủ sâu để chứng minh Java backend và distributed-system
reliability, nhưng đủ nhỏ để một người có thể vận hành và giải thích.

## Phạm vi

- Payment create/search/detail/cancel và full/partial refund.
- Risk decision, account reserve/capture/release, double-entry journal.
- Saga compensation, retry hữu hạn, DLT và manual review.
- Notification/webhook HMAC + retry.
- Settlement/reconciliation.
- Keycloak OAuth2/OIDC, Gateway và service-level authorization.
- PostgreSQL, Kafka, Redis, Flyway, Swagger và Docker Compose.

## Ngoài phạm vi

- Tiền/thẻ/ngân hàng/PSP/payout thật.
- KYC, PCI production certification, machine-learning fraud.
- Merchant-management service và reporting dashboard riêng.
- Kubernetes và multi-region production deployment.

## Bằng chứng portfolio

1. Idempotency key không tạo payment/refund trùng.
2. Inbox chống Kafka redelivery; Outbox không làm mất event sau DB commit.
3. Balance/reservation và immutable balanced journal giữ invariant.
4. Saga bù trừ reserve khi ledger thất bại.
5. JWT scope + merchant ownership được kiểm ở Gateway và service.
6. Smoke test chạy luồng payment end-to-end.
