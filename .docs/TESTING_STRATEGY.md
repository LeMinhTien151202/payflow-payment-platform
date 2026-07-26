# Chiến lược kiểm thử PayFlow

## 1. Mục tiêu

Test không chỉ chứng minh endpoint trả đúng JSON. Test phải chứng minh các invariant vẫn đúng dưới duplicate delivery, concurrency, timeout, retry và process restart.

## 2. Test pyramid

| Loại | Mục tiêu | Công cụ dự kiến | Chạy khi |
| --- | --- | --- | --- |
| Unit | Money policy, state transition, rule, journal validation | JUnit 5, AssertJ, Mockito chỉ ở boundary | Mọi change |
| Web/security slice | Route, validation, Problem Details, 401/403, ownership | Spring MVC/WebFlux Test, Spring Security Test | API/Gateway change |
| Repository | JPA mapping, PostgreSQL constraint/index/locking | Testcontainers PostgreSQL | Entity/query/migration change |
| Messaging integration | Serializer, key/header, outbox publisher, inbox transaction, Kafka ack | Spring Kafka Test + Testcontainers Kafka | Event producer/consumer change |
| Redis integration | Rate/velocity/expiry/atomic operation | Testcontainers Redis | Risk/rate-limit change |
| Component | Một service với dependency thật và external stub | `@SpringBootTest`, Testcontainers, WireMock | Service milestone |
| Contract | REST và event compatibility/versioning | OpenAPI/JSON fixture, Spring Cloud Contract/Pact khi chọn | Contract change |
| E2E | Workflow xuyên deployable | Docker Compose + REST Assured/Postman/Newman | Phase gate |
| Chaos/recovery | Crash window, timeout, duplicate, partition recovery | Toxiproxy/kill container/test hook | Pre-Phase-2 reliability gate+ |
| Load | Throughput, P95/P99, saturation, correctness dưới tải | k6 | Phase 3 |

Không dùng H2 để chứng minh PostgreSQL SQL, transaction, constraint hoặc lock behavior.

## 3. Mandatory invariant suite

### Payment và idempotency

- Tạo payment hợp lệ trả 202 và đúng initial state.
- Cùng idempotency key/cùng canonical payload trả cùng resource/response.
- Cùng key/khác payload trả 409 với stable error code.
- Hai request đồng thời cùng key chỉ tạo một payment và một logical event.
- Merchant reference unique trong merchant, không vô tình unique toàn hệ thống.
- Invalid state transition bị reject và không tạo outbox event sai.
- Saga timeout kích hoạt policy recovery/compensation đúng một lần.

### Account

- Reserve đủ tiền chuyển đúng `available -> reserved`.
- Reserve thiếu tiền không thay đổi số dư.
- Nhiều reserve đồng thời không làm số dư âm.
- Duplicate reserve không giữ tiền lần hai.
- Capture/release chỉ từ reservation hợp lệ và idempotent khi delivery trùng.
- Account frozen bị từ chối.
- Reservation expiry không release reservation đã captured.

### Ledger

- Journal cân bằng được post atomically.
- Journal lệch, amount <= 0 hoặc mixed currency bị reject.
- Duplicate reference/journal type không tạo journal thứ hai.
- Posted journal không update/delete qua application API.
- Refund/reversal tạo journal mới có linkage rõ ràng.
- Failure giữa journal và entries rollback toàn transaction.

### Refund

- Chỉ refund payment đủ điều kiện.
- Partial refund cập nhật đúng remaining refundable amount và status.
- Hai/nhiều refund đồng thời không vượt original amount.
- Duplicate refund request/event không credit hoặc post ledger hai lần.
- Refund failure giữ state/retry/compensation nhất quán.

### Messaging reliability

- Business transaction rollback thì không tồn tại outbox event tương ứng.
- Business commit thì outbox tồn tại dù publisher chưa chạy.
- Crash sau Kafka publish trước mark-published tạo duplicate có kiểm soát; consumer không tạo side effect trùng.
- Consumer business failure rollback cả processed-event marker.
- Duplicate event sau success được acknowledge/no-op.
- Poison event retry hữu hạn rồi tới DLT, không loop vô hạn.
- Event cùng aggregate giữ ordering assumption đã định nghĩa; event khác aggregate không yêu cầu global order.

### Security

- Token thiếu/sai/hết hạn trả 401; token hợp lệ nhưng thiếu quyền trả 403.
- Customer/merchant không truy cập resource của tenant khác bằng đổi ID.
- Internal endpoint không được public principal gọi.
- API key chỉ trả plaintext một lần và database không chứa plaintext.
- Log/error response không chứa token, secret, credential hoặc stack trace.
- Webhook signature đúng raw body/timestamp; stale/replayed signature bị từ chối trong verifier fixture.

## 4. Scenario E2E chuẩn

### Successful payment

```text
Given customer available balance = 1,000,000 VND
When payment = 500,000 VND and risk approves
Then one reservation is created
And one balanced capture journal is posted
And reservation reaches CAPTURED
And payment reaches SUCCEEDED according to accepted ADR-011 ordering
And notification/webhook record exists
And trace can be found by correlationId/paymentId
```

### Insufficient funds

```text
Given available balance = 100,000 VND
When payment = 500,000 VND
Then payment fails with ACCOUNT_INSUFFICIENT_FUNDS
And no journal is posted
And balance is unchanged
```

### Ledger outage compensation

```text
Given reserve succeeds
When Ledger times out beyond bounded retry
Then Saga requests release once
And balance returns to the initial amount
And no capture journal is posted
And Saga/payment states match the resolved OD-006 contract
```

### Partial refund

```text
Given succeeded payment = 500,000 VND
When refunds 200,000 then 300,000 VND succeed
Then statuses move PARTIALLY_REFUNDED -> REFUNDED
And refund journals balance
And any further refund is rejected
```

## 5. Failure injection matrix

| Injection point | Điều cần chứng minh |
| --- | --- |
| Sau business commit, trước outbox poll | Event cuối cùng vẫn được publish |
| Sau Kafka send, trước outbox mark | Duplicate không tạo side effect trùng |
| Sau inbox insert, trước business update | Transaction rollback cho phép retry đầy đủ |
| Risk/Account/Ledger consumer restart | Offset + local state phục hồi nhất quán |
| Ledger unavailable | Retry hữu hạn rồi compensation/manual review |
| Webhook 429/5xx/timeout | Retry schedule đúng; payment path không bị block |
| Webhook permanent 4xx | Không retry mù; delivery terminal/auditable |
| Redis unavailable | Balance/ledger correctness không phụ thuộc Redis |

## 6. Test data

- Chỉ dùng UUID, merchant, customer, account, email, IP và webhook endpoint giả.
- Tiền fixture ghi cả amount và currency.
- Clock/UUID/event ID nên injectable ở domain/application test để kết quả deterministic.
- Test bất đồng bộ dùng eventually/poll có deadline; không dùng `Thread.sleep` dài và flaky.
- Test concurrency dùng barrier/latch và kiểm tra database state sau khi tất cả worker kết thúc.

## 7. CI gate dự kiến

1. Format/static analysis và compile.
2. Unit + web/security tests.
3. Migration/repository tests với PostgreSQL.
4. Kafka/Redis integration tests.
5. Contract/component tests.
6. Package từng service và build Docker image non-root.
7. Vulnerability/secret scan.
8. E2E/chaos/load chạy theo milestone hoặc workflow riêng phù hợp chi phí.

Không gọi provider hoặc endpoint thật trong CI mặc định.

## 8. Performance evidence cho portfolio

Mỗi report phải lưu:

- commit SHA và ngày chạy;
- CPU/RAM/OS/container allocation;
- số instance, partition, connection pool và dataset;
- k6 script/config, duration, concurrency và success criteria;
- throughput, P50/P95/P99, error rate, Saga duration, consumer lag;
- invariant check sau tải: không duplicate payment, không âm balance, journal cân bằng;
- bottleneck và giới hạn đã biết.

Không so sánh số liệu giữa hai lần chạy khác môi trường mà không ghi chú.

## 9. Lệnh kiểm chứng

Khi Maven modules được tạo, dùng Maven Wrapper của repository. Tên profile/test group phải được khóa trong parent POM trước khi đưa lệnh cụ thể vào đây. Cho tới lúc đó, không tuyên bố bất kỳ lệnh build/test nào đã pass.
