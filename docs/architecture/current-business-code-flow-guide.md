# PayFlow hiện tại: nghiệp vụ thật, luồng code và dữ liệu đi qua từng service

Tài liệu này mô tả **hệ thống đang được implement trong code hiện tại**, không phải roadmap tương lai. Mục tiêu là giúp người mới mở dự án hiểu được:

- API nào là điểm bắt đầu.
- Service nào xử lý việc gì.
- Dữ liệu nào được truyền qua HTTP/Kafka.
- Transaction nào ghi database.
- Vì sao có Outbox, Inbox và Saga.
- Khi payment/refund thành công hoặc lỗi thì code đi từ đâu đến đâu.

Nếu chỉ cần một câu ngắn: **client gọi REST vào API Gateway, Payment Service nhận yêu cầu và trả `202 Accepted`, sau đó các service tự nói chuyện với nhau bằng Kafka event cho đến khi payment/refund kết thúc.**

---

## 1. Bức tranh tổng thể

PayFlow hiện tại là một hệ thống thanh toán mô phỏng theo kiểu event-driven microservices.

Có 5 service chính:

| Service | Vai trò hiện tại | Có sở hữu dữ liệu nghiệp vụ không? |
| --- | --- | --- |
| `api-gateway` | Cửa vào HTTP. Kiểm tra JWT/scope/correlation id và route request đến Payment Service. | Không sở hữu payment/account/ledger. |
| `payment-service` | Nhận payment/refund từ REST, lưu trạng thái payment/refund, điều phối Saga, chống request trùng bằng idempotency. | Có. Sở hữu payment, refund, saga, outbox/inbox của payment. |
| `risk-service` | Nghe `payment.created`, chấm điểm rủi ro, phát kết quả risk. | Có. Sở hữu risk assessment. |
| `account-ledger-service` | Một service nhưng có 2 biên nội bộ: Account giữ số dư/reservation/capture/release/refund credit; Ledger ghi journal bất biến. | Có. Sở hữu account balance/reservation và ledger journal. |
| `notification-service` | Nghe kết quả cuối payment/refund, tạo notification, worker gửi/mock gửi email. | Có. Sở hữu notification. |

Kafka không thay REST hoàn toàn. REST dùng cho client gọi vào hệ thống. Kafka dùng cho các service nội bộ chạy tiếp luồng xử lý sau khi HTTP đã trả về.

---

## 2. Ba cơ chế cần hiểu trước

### 2.1. Transactional Outbox

Trong PayFlow, khi một service thay đổi database và cần phát Kafka event, service **không gửi Kafka trực tiếp trong transaction nghiệp vụ**.

Thay vào đó:

1. Ghi dữ liệu nghiệp vụ vào DB.
2. Ghi thêm một dòng vào bảng `outbox_events` trong **cùng transaction**.
3. Transaction commit.
4. Job publish outbox đọc dòng đó, gửi Kafka, rồi đánh dấu đã publish.

Ví dụ khi tạo payment:

- Payment Service lưu payment.
- Payment Service lưu saga.
- Payment Service ghi outbox event `payment.created`.
- HTTP trả `202`.
- Sau đó outbox publisher mới gửi `payment.created` lên Kafka.

Lý do: tránh tình huống DB đã commit nhưng Kafka gửi lỗi, hoặc Kafka gửi rồi DB rollback. Outbox giúp hệ thống có một nguồn thật trong DB để retry.

Class quan trọng:

- Payment intake: `CreatePaymentHandler`.
- Payment outbox publisher: `PublishOutboxHandler`.
- Kafka transport: `KafkaOutboxTransport`.

### 2.2. Transactional Inbox / processed events

Kafka là at-least-once: một event có thể được giao lại nhiều lần. Vì vậy consumer phải chống xử lý trùng.

Mỗi service consumer làm như sau trong transaction local:

1. Insert marker vào bảng `processed_events` với `(event_id, consumer_name)`.
2. Nếu insert không được vì đã có marker, trả `DUPLICATE` và không xử lý lại.
3. Nếu là event mới, thực hiện business mutation.
4. Ghi outbox event tiếp theo nếu cần.
5. Commit.
6. Listener mới acknowledge Kafka.

Lý do: nếu service crash trước khi ack Kafka, Kafka có thể giao lại event. Inbox đảm bảo event đó không bị apply hai lần.

### 2.3. Saga

Saga là bộ điều phối một giao dịch dài qua nhiều service, vì không có một transaction DB chung cho tất cả service.

Trong PayFlow, **Payment Service là orchestrator**. Nghĩa là Payment Service lưu `payment_sagas` và quyết định bước tiếp theo:

1. Chờ risk.
2. Yêu cầu account reserve tiền.
3. Yêu cầu ledger post journal.
4. Yêu cầu account capture tiền.
5. Kết thúc thành công hoặc đi compensation/manual review.

Saga lưu các “sự thật tài chính” quan trọng như:

- `reservationId`: account đã giữ tiền thành công.
- `journalId`: ledger đã ghi sổ thành công.

Điểm cực quan trọng: **nếu ledger journal đã tồn tại thì hệ thống không tự release tiền như thể chưa có gì xảy ra**. Ledger là bất biến. Nếu đã ghi journal mà có lỗi tiếp theo không xử lý an toàn được, Saga chuyển sang `MANUAL_REVIEW_REQUIRED`.

---

## 3. Các topic và event chính

Các topic hiện tại nằm trong `PayFlowTopics`:

| Topic | Dùng cho |
| --- | --- |
| `payflow.payment.events.v1` | Payment events và command nội bộ do Payment Saga phát. |
| `payflow.risk.events.v1` | Kết quả risk. |
| `payflow.account.events.v1` | Account result events. |
| `payflow.ledger.events.v1` | Ledger result events. |
| `payflow.refund.events.v1` | Refund request và refund outcome. |
| `payflow.notification.commands.v1` | Topic notification command, hiện không phải trục chính của flow mô tả dưới đây. |
| `payflow.dead-letter.v1` | Poison/dead-letter events. |

Trong Kafka record, key thường là `paymentId` dưới dạng string. Điều này giúp các event của cùng một payment đi cùng partition và giữ thứ tự theo từng payment.

---

## 4. Luồng gọi `POST /api/v1/payments`

### 4.1. Client gọi vào đâu?

Client gọi:

```http
POST /api/v1/payments
Authorization: Bearer <jwt>
Idempotency-Key: pay-order-2026-00001-v1
Content-Type: application/json
```

Body có các dữ liệu kiểu:

```json
{
  "customerId": "...",
  "sourceAccountId": "...",
  "merchantReference": "ORDER-00001",
  "amount": "100.0000",
  "currency": "VND",
  "description": "...",
  "metadata": {}
}
```

Điểm quan trọng:

- `merchantId` **không được tin từ body**. Controller lấy `merchant_id` từ JWT.
- `Idempotency-Key` bắt buộc để chống client retry tạo nhiều payment.
- HTTP response `202` chỉ nghĩa là “hệ thống đã nhận và lưu yêu cầu”, không có nghĩa payment đã thành công.

### 4.2. API Gateway làm gì?

`api-gateway` nhận request ở cổng gateway, kiểm tra security/correlation id rồi route `/api/v1/payments/**` sang Payment Service.

Gateway không ghi payment, không giữ tiền, không phát Kafka cho nghiệp vụ payment.

### 4.3. Payment Controller làm gì?

Trong Payment Service, request vào `PaymentController`:

- Method: `create(...)`.
- Lấy `merchant_id` từ JWT.
- Kiểm tra `Idempotency-Key`.
- Convert body thành `CreatePaymentCommand`.
- Gọi `CreatePaymentHandler.handle(...)`.
- Bọc response trong `ApiResponse` có `correlationId` và timestamp.

---

## 5. Happy path payment: từng bước từ HTTP đến thành công

Phần này là luồng thành công lý tưởng:

```text
Client
  -> API Gateway
  -> Payment Service
  -> Kafka payment.created
  -> Risk Service
  -> Kafka risk.assessment.completed
  -> Payment Saga
  -> Kafka account.reserve.requested
  -> Account boundary
  -> Kafka account.funds-reserved
  -> Payment Saga
  -> Kafka ledger.post-payment.requested
  -> Ledger boundary
  -> Kafka ledger.payment-posted
  -> Payment Saga
  -> Kafka account.capture.requested
  -> Account boundary
  -> Kafka account.funds-captured
  -> Payment Saga
  -> Kafka payment.succeeded
  -> Notification Service
```

### T1 — Payment Service nhận payment trong một local transaction

Class chính: `CreatePaymentHandler`.

Trong `handle(...)`:

1. Tạo idempotency scope theo merchant: `createPayment(merchantId)`.
2. Tạo fingerprint từ request.
3. Đọc idempotency trước transaction. Nếu đã có response cũ cùng fingerprint, replay response.
4. Nếu chưa có, mở transaction bằng `TransactionTemplate`.

Trong transaction `create(...)`, Payment Service làm:

1. Lấy `now` từ clock.
2. Tìm merchant trong catalog.
3. Tạo `PaymentIntake` từ request.
4. Tạo aggregate `Payment.create(...)`.
5. Tạo snapshot response ban đầu `PaymentAcceptance.of(payment)`.
6. Chuyển payment sang bước gửi risk bằng `payment.submitForRisk(now)`.
7. Ghi idempotency record kèm response replay.
8. Lưu payment.
9. Tạo saga bằng `PaymentSaga.start(...)`.
10. Ghi outbox event `payment.created` vào topic `payflow.payment.events.v1`.

Payload event:

```java
PaymentCreatedData(
    paymentId,
    merchantId,
    customerId,
    sourceAccountId,
    amount,
    currency,
    createdAt
)
```

Sau commit, HTTP trả `202 Accepted` cho client. Tại thời điểm này risk/account/ledger chưa chạy xong.

Saga lúc này:

- Status: `RUNNING`.
- Step đầu: chờ risk assessment.
- Có deadline để recovery job biết khi nào cần retry nếu event bị mất/chậm.

### T2 — Outbox publisher gửi `payment.created` lên Kafka

Class chính:

- `PublishOutboxHandler`.
- `KafkaOutboxTransport`.

Outbox publisher:

1. Claim một batch outbox row.
2. Gửi Kafka record:
   - topic: `payflow.payment.events.v1`
   - key: `paymentId`
   - payload: envelope chứa `payment.created` và `PaymentCreatedData`
3. Nếu publish thành công, đánh dấu row đã published.
4. Nếu lỗi, mark retry/failed theo policy.

Nếu một event cho cùng aggregate lỗi, các event sau cùng aggregate bị chặn để tránh đảo thứ tự.

### T3 — Risk Service nghe `payment.created`

Class chính:

- `RiskPaymentKafkaListener`.
- `RiskPaymentEventRouter`.
- `HandlePaymentCreatedHandler`.

Listener của Risk Service nghe topic `payflow.payment.events.v1`, group id `risk-payment-created-v1`.

Router chỉ xử lý event type `payment.created`; event khác thì ignore.

Handler làm:

1. Validate contract event.
2. Collect risk signal snapshot, có thể dùng Redis/signal provider.
3. Mở local transaction.
4. Ghi inbox marker.
5. Nếu trùng event thì trả duplicate.
6. Nếu đã có assessment cùng `paymentId`, trả business duplicate.
7. Chạy `RiskRuleEngine`.
8. Lưu risk assessment.
9. Ghi outbox event `risk.assessment.completed` vào topic `payflow.risk.events.v1`.

Payload:

```java
RiskAssessmentCompletedData(
    paymentId,
    decision,
    score,
    level,
    matchedRules,
    policyVersion
)
```

Kafka ack chỉ xảy ra sau khi handler hoàn tất thành công.

### T4 — Payment Saga nhận kết quả risk

Class chính:

- `PaymentWorkflowKafkaListener`.
- `PaymentWorkflowEventRouter`.
- `HandlePaymentWorkflowEventHandler.handleRiskAssessment(...)`.

Payment Workflow listener nghe:

- `payflow.risk.events.v1`
- `payflow.account.events.v1`
- `payflow.ledger.events.v1`

Với `risk.assessment.completed`, handler mở một transaction và làm:

1. Ghi inbox marker với consumer `payment-saga-orchestrator-v1`.
2. Load `Payment` bằng `findForWorkflow(paymentId)`.
3. Load `PaymentSaga` bằng `findByPaymentId(paymentId)`.
4. Áp dụng `ApplyRiskAssessmentPolicy`.
5. Nếu risk approve: `saga.recordRiskApproved(...)`.
6. Ghi outbox event `account.reserve.requested` vào topic `payflow.payment.events.v1`.

Payload command reserve:

```java
AccountReserveRequestedData(
    paymentId,
    accountId,
    amount,
    currency,
    expiresAt
)
```

Saga sau bước này:

- Status: `RUNNING`.
- Step: chờ reserve funds.
- Có deadline mới.

Nếu risk reject, Payment Service không reserve tiền mà phát `payment.failed`.
Nếu risk cần review, Payment Service phát `payment.manual-review-required`.

### T5 — Account boundary reserve tiền

Class chính:

- `AccountLedgerWorkflowKafkaListener`.
- `AccountLedgerWorkflowEventRouter`.
- `HandleReserveFundsRequestedHandler`.

Account-Ledger Service nghe `payflow.payment.events.v1` và `payflow.refund.events.v1`, group id `account-ledger-workflow-v1`.

Router xử lý `account.reserve.requested`.

Handler reserve làm trong một local transaction:

1. Ghi inbox marker.
2. Kiểm tra account/balance/currency/reservation policy.
3. Nếu đủ điều kiện, tạo reservation ACTIVE và giữ tiền.
4. Nếu không đủ điều kiện, ghi outcome failed.
5. Ghi outbox event kết quả.

Nếu thành công, event là `account.funds-reserved`.

Payload:

```java
AccountFundsReservedData(
    paymentId,
    accountId,
    reservationId,
    amount,
    currency
)
```

Nếu thất bại, event là `account.funds-reservation-failed`.

Payload:

```java
AccountFundsReservationFailedData(
    paymentId,
    accountId,
    reasonCode
)
```

### T6 — Payment Saga nhận `account.funds-reserved`

Handler: `HandlePaymentWorkflowEventHandler.handleFundsReserved(...)`.

Trong một transaction:

1. Ghi inbox marker.
2. Load Payment và Saga.
3. Validate reservation result khớp payment.
4. Lưu `reservationId` vào Saga bằng `saga.recordFundsReserved(...)`.
5. Ghi outbox command `ledger.post-payment.requested`.

Payload:

```java
LedgerPostPaymentRequestedData(
    paymentId,
    customerId,
    merchantId,
    amount,
    currency
)
```

Saga sau bước này:

- Status: `RUNNING`.
- Step: chờ ledger post.
- Đã có `reservationId`.
- Chưa có `journalId`.

### T7 — Ledger boundary ghi journal payment

Handler: `HandlePostPaymentRequestedHandler`.

Ledger boundary xử lý `ledger.post-payment.requested` trong Account-Ledger Service.

Trong một local transaction:

1. Ghi inbox marker.
2. Kiểm tra command.
3. Ghi immutable journal loại payment capture.
4. Ghi outbox event kết quả.

Nếu thành công, event là `ledger.payment-posted`.

Payload:

```java
LedgerPaymentPostedData(
    paymentId,
    journalId,
    amount,
    currency
)
```

Nếu thất bại, event là `ledger.payment-posting-failed`.

### T8 — Payment Saga nhận `ledger.payment-posted`

Handler: `HandlePaymentWorkflowEventHandler.handleLedgerPosted(...)`.

Trong một transaction:

1. Ghi inbox marker.
2. Load Payment và Saga.
3. Kiểm tra Saga đã có `reservationId`.
4. Lưu `journalId` bằng `saga.recordLedgerPosted(...)`.
5. Ghi outbox command `account.capture.requested`.

Payload:

```java
AccountCaptureRequestedData(
    paymentId,
    accountId,
    reservationId,
    amount,
    currency
)
```

Saga sau bước này:

- Status: `RUNNING`.
- Step: chờ capture funds.
- Đã có `reservationId`.
- Đã có `journalId`.

Từ thời điểm này, ledger journal đã tồn tại. Đây là ranh giới quan trọng: không được tự động release như trước khi ghi sổ.

### T9 — Account boundary capture tiền

Handler: `HandleCaptureFundsRequestedHandler`.

Trong một local transaction:

1. Ghi inbox marker.
2. Tìm reservation ACTIVE khớp `paymentId`, `accountId`, `reservationId`, amount, currency.
3. Capture reservation.
4. Cập nhật balance theo nghĩa capture.
5. Ghi outbox event `account.funds-captured`.

Payload:

```java
AccountFundsCapturedData(
    paymentId,
    accountId,
    reservationId,
    amount,
    currency,
    capturedAt
)
```

### T10 — Payment Saga nhận `account.funds-captured`

Handler: `HandlePaymentWorkflowEventHandler.handleFundsCaptured(...)`.

Trong một transaction:

1. Ghi inbox marker.
2. Load Payment và Saga.
3. Dựng lại reservation fact từ `reservationId` trong Saga.
4. Dựng lại ledger fact từ `journalId` trong Saga.
5. Gọi `PaymentFinalizationPolicy.complete(...)`.
6. Chuyển payment sang succeeded.
7. `saga.complete(now)`.
8. Ghi outbox event `payment.succeeded`.

Payload:

```java
PaymentSucceededData(
    paymentId,
    merchantId,
    customerId,
    amount,
    currency,
    completedAt
)
```

Saga sau bước này:

- Status: `COMPLETED`.
- Step: `COMPLETED`.
- Payment là thành công.

### T11 — Notification Service nhận `payment.succeeded`

Class chính:

- `NotificationOutcomeKafkaListener`.
- `NotificationOutcomeEventRouter`.
- `OutcomeNotificationFactory`.
- `CreateOutcomeNotificationHandler`.

Notification Service nghe:

- `payflow.payment.events.v1`
- `payflow.refund.events.v1`

Router chỉ xử lý outcome cuối:

- `payment.succeeded`
- `payment.failed`
- `refund.succeeded`
- `refund.failed`

Với `payment.succeeded`, service tạo notification intent rồi lưu row notification.

Trong transaction:

1. Ghi inbox marker.
2. Kiểm tra business duplicate theo business reference.
3. Insert notification nếu chưa có.
4. Commit.
5. Kafka ack.

Notification insert hiện dùng:

- `created_at` = `event.occurredAt()` của upstream event, phục vụ audit/lag.
- `next_attempt_at` = `clock_timestamp()` của database notification, phục vụ scheduling delivery.

### T12 — Notification delivery worker gửi email/mock email

Class chính:

- `DeliverPendingNotificationsHandler`.
- `NotificationDeliveryStore`.
- `JdbcNotificationDeliveryStore`.

Worker claim notification đến hạn bằng database clock, không dùng clock local của worker để quyết định due/lease.

Luồng:

1. Claim batch notification `PENDING` hoặc lease `PROCESSING` đã hết hạn.
2. Đặt `status = PROCESSING`, tăng `attempt_count`, set `lock_owner`, `lock_until` bằng DB clock.
3. Gửi/mock gửi email.
4. Nếu thành công: `markSent(notificationId, owner, sentAt)`.
5. Nếu lỗi: `markFailed(...)`, có retry theo attempt policy.

`sentAt` vẫn do caller truyền vào vì đó là audit fact, không phải predicate scheduling.

---

## 6. Các nhánh lỗi chính của payment

### 6.1. Risk reject

Nếu Risk Service trả decision bị reject:

1. Payment Saga nhận `risk.assessment.completed`.
2. `ApplyRiskAssessmentPolicy` trả action `PUBLISH_PAYMENT_FAILED`.
3. Saga gọi `failBeforeLedger(...)` vì chưa có journal.
4. Payment Service phát `payment.failed`.

Payload:

```java
PaymentFailedData(
    paymentId,
    failureCode,
    failedAt
)
```

Notification Service sẽ nghe `payment.failed` và tạo notification thất bại.

### 6.2. Reserve tiền thất bại

Nếu Account không reserve được tiền:

1. Account-Ledger phát `account.funds-reservation-failed`.
2. Payment Saga nhận event.
3. Saga gọi `failBeforeLedger(...)`.
4. Payment Service phát `payment.failed`.

Vì chưa có ledger journal nên failure này còn đơn giản: chưa ghi sổ, chưa capture.

### 6.3. Ledger posting thất bại trước khi có journal

Nếu Ledger không post được journal và gửi `ledger.payment-posting-failed`:

1. Payment Saga nhận event.
2. `PaymentSagaRecoveryPolicy.onLedgerPostingFailed(...)` quyết định hành động.
3. Nếu đã reserve tiền nhưng chưa có `journalId`, Saga có thể compensation bằng release.
4. Payment Service phát command `account.release.requested`.

Payload:

```java
AccountReleaseRequestedData(
    paymentId,
    accountId,
    reservationId,
    amount,
    currency,
    reasonCode
)
```

Sau đó Account boundary release reservation và phát:

```java
AccountFundsReleasedData(
    paymentId,
    accountId,
    reservationId,
    amount,
    currency,
    reasonCode,
    releasedAt
)
```

Payment Saga nhận `account.funds-released`, xác nhận release khớp reservation, rồi phát `payment.failed`.

### 6.4. Vì sao có `MANUAL_REVIEW_REQUIRED`?

`MANUAL_REVIEW_REQUIRED` là điểm dừng an toàn khi hệ thống không thể tự sửa mà không có rủi ro làm sai tiền.

Ví dụ quan trọng:

- Nếu Saga đã có `journalId`, tức ledger đã ghi journal bất biến.
- Sau đó có lỗi không thể tự hoàn tất/capture/reconcile an toàn.
- Hệ thống không được tự release reservation như thể ledger chưa ghi gì.
- Vì vậy Payment Service phát `payment.manual-review-required`.

Payload:

```java
PaymentManualReviewRequiredData(
    paymentId,
    sagaStep,
    reasonCode
)
```

Ý nghĩa nghiệp vụ: cần người vận hành kiểm tra ledger/account/payment rồi xử lý theo runbook, thay vì code tự đoán.

---

## 7. Recovery khi event bị mất, chậm hoặc không có response

Class chính: `RecoverOverdueSagasHandler`.

Mỗi Saga step có deadline. Nếu quá hạn mà chưa nhận được event tiếp theo, recovery job xử lý.

Luồng recovery:

1. Lấy `now`.
2. Tìm các saga đến hạn bằng `sagas.findDueIds(now, batchSize)`.
3. Với từng saga, mở transaction riêng.
4. Load Saga và Payment.
5. Gọi `PaymentSagaRecoveryPolicy.onDeadline(...)`.
6. Policy quyết định:
   - retry command hiện tại;
   - compensation release funds;
   - manual review;
   - hoặc no action.
7. Update Saga/Payment.
8. Ghi outbox event tương ứng.

Retry command theo step hiện tại:

| Saga step | Event retry |
| --- | --- |
| `RISK_ASSESSMENT` | phát lại `payment.created` |
| `RESERVE_FUNDS` | phát lại `account.reserve.requested` |
| `POST_LEDGER` | phát lại `ledger.post-payment.requested` |
| `CAPTURE_FUNDS` | phát lại `account.capture.requested` |
| `RELEASE_FUNDS` | phát lại `account.release.requested` |

Recovery không biến hệ thống thành exactly-once. Nó vẫn dựa trên at-least-once + idempotent consumer.

---

## 8. Luồng `GET /api/v1/payments/{paymentId}`

Client dùng GET để polling sau khi POST trả `202`.

Luồng:

1. Client gọi Gateway.
2. Gateway route đến Payment Service.
3. `PaymentController.get(...)` lấy `merchant_id` từ JWT.
4. Gọi `GetPaymentHandler.handle(paymentId, merchantId)`.
5. Handler chỉ đọc payment thuộc merchant đó.
6. API trả trạng thái hiện tại.

GET không phát Kafka event.

Trạng thái có thể là đang xử lý hoặc terminal, ví dụ:

- đang risk/reserve/ledger/capture;
- `SUCCEEDED`;
- `FAILED`;
- `MANUAL_REVIEW_REQUIRED`;
- trạng thái refund như partially refunded/refunded tùy workflow refund đã đi tới đâu.

---

## 9. Luồng gọi refund: `POST /api/v1/payments/{paymentId}/refunds`

Refund bắt đầu sau khi payment đã tồn tại và thuộc merchant.

Client gọi:

```http
POST /api/v1/payments/{paymentId}/refunds
Authorization: Bearer <jwt>
Idempotency-Key: refund-order-2026-00001-v1
Content-Type: application/json
```

Body có amount/currency/lý do tùy request contract hiện tại.

### R1 — Payment Service nhận refund

Class chính:

- `PaymentController.refund(...)`.
- `CreateRefundHandler`.

Controller:

1. Lấy `paymentId` từ path.
2. Lấy `merchant_id` từ JWT.
3. Lấy actor id từ JWT subject.
4. Kiểm tra `Idempotency-Key`.
5. Convert body thành `CreateRefundCommand`.
6. Gọi `CreateRefundHandler.handle(...)`.

`CreateRefundHandler` làm:

1. Tạo idempotency scope `createRefund(merchantId)`.
2. Tạo request fingerprint.
3. Nếu idempotency response đã có, replay.
4. Nếu chưa có, mở transaction.

Trong transaction:

1. Lock payment bằng `findForRefund(paymentId, merchantId)`.
2. Re-check idempotency sau khi lock.
3. Tạo `Money` refund amount.
4. Gọi `payment.reserveRefund(requested, now)` để giữ refund capacity trên Payment aggregate.
5. Tạo `Refund`.
6. Ghi idempotent response.
7. Update refund state của payment.
8. Save refund.
9. Ghi outbox event `refund.requested` vào topic `payflow.refund.events.v1`.

Payload:

```java
RefundRequestedData(
    refundId,
    paymentId,
    merchantId,
    customerId,
    accountId,
    amount,
    currency,
    requestedAt
)
```

HTTP trả `202 Accepted`. Refund chưa chắc đã hoàn tất.

### R2 — Account-Ledger Service post refund ledger journal

Account-Ledger router nhận `refund.requested`.

Handler: `HandleRefundRequestedHandler`.

Trong transaction:

1. Ghi inbox marker.
2. Post immutable refund reversal journal.
3. Ghi outbox result.

Nếu thành công:

```java
LedgerRefundPostedData(
    refundId,
    paymentId,
    journalId,
    accountId,
    amount,
    currency
)
```

Nếu thất bại:

```java
LedgerRefundPostingFailedData(
    refundId,
    paymentId,
    amount,
    currency,
    failureCode
)
```

### R3 — Payment refund orchestrator nhận `ledger.refund-posted`

Class chính: `HandleRefundWorkflowEventHandler`.

Khi nhận `ledger.refund-posted`, Payment Service:

1. Ghi inbox marker với consumer `payment-refund-orchestrator-v1`.
2. Lock Payment rồi Refund theo cùng thứ tự với refund intake.
3. Lưu `ledgerJournalId` cho refund.
4. Ghi outbox command `account.refund-credit.requested` vào topic `payflow.payment.events.v1`.

Payload:

```java
AccountRefundCreditRequestedData(
    refundId,
    paymentId,
    accountId,
    journalId,
    amount,
    currency
)
```

### R4 — Account boundary credit tiền refund

Handler: `HandleRefundCreditRequestedHandler`.

Trong transaction:

1. Ghi inbox marker.
2. Credit tiền refund vào account.
3. Ghi credit id.
4. Ghi outbox event `account.refund-credited`.

Payload:

```java
AccountRefundCreditedData(
    refundId,
    paymentId,
    accountId,
    journalId,
    creditId,
    amount,
    currency
)
```

### R5 — Payment refund orchestrator hoàn tất refund

`HandleRefundWorkflowEventHandler.handleAccountRefundCredited(...)` nhận `account.refund-credited`.

Trong transaction:

1. Ghi inbox marker.
2. Lock Payment và Refund.
3. Kiểm tra refund đã có `ledgerJournalId`.
4. Complete refund workflow.
5. Update payment refund state.
6. Ghi outbox event `refund.succeeded` vào topic `payflow.refund.events.v1`.

Payload:

```java
RefundSucceededData(
    refundId,
    paymentId,
    merchantId,
    journalId,
    creditId,
    amount,
    feeReversalAmount,
    currency,
    completedAt
)
```

Notification Service nghe `refund.succeeded` và tạo notification.

### R6 — Refund thất bại trước journal

Nếu Ledger refund posting thất bại trước khi có journal, Payment refund orchestrator nhận `ledger.refund-posting-failed` và phát:

```java
RefundFailedData(
    refundId,
    paymentId,
    merchantId,
    amount,
    currency,
    failureCode,
    failedAt
)
```

Notification Service nghe `refund.failed` và tạo notification thất bại.

---

## 10. Service nhận event nào và bỏ qua event nào?

### Payment Service workflow listener

Nghe:

- `payflow.risk.events.v1`
- `payflow.account.events.v1`
- `payflow.ledger.events.v1`

Xử lý:

- `risk.assessment.completed`
- `account.funds-reserved`
- `account.funds-reservation-failed`
- `account.funds-captured`
- `account.funds-released`
- `ledger.payment-posted`
- `ledger.payment-posting-failed`
- `ledger.refund-posted`
- `ledger.refund-posting-failed`
- `account.refund-credited`

### Risk Service

Nghe:

- `payflow.payment.events.v1`

Chỉ xử lý:

- `payment.created`

Event khác thì ignore.

### Account-Ledger Service

Nghe:

- `payflow.payment.events.v1`
- `payflow.refund.events.v1`

Xử lý command:

- `account.reserve.requested`
- `account.capture.requested`
- `account.release.requested`
- `ledger.post-payment.requested`
- `refund.requested`
- `account.refund-credit.requested`

### Notification Service

Nghe:

- `payflow.payment.events.v1`
- `payflow.refund.events.v1`

Chỉ xử lý outcome cuối:

- `payment.succeeded`
- `payment.failed`
- `refund.succeeded`
- `refund.failed`

---

## 11. Vì sao nhìn phức tạp?

Luồng này phức tạp vì nó cố mô phỏng những vấn đề thật của payment system:

1. **HTTP retry**: client gửi lại request có thể tạo trùng tiền nếu không có idempotency.
2. **Kafka duplicate**: event có thể được giao nhiều lần nếu consumer crash trước ack.
3. **DB và Kafka không chung transaction**: cần outbox để không mất event.
4. **Nhiều service không chung database**: cần Saga để điều phối.
5. **Tiền không được sửa bừa**: đã ledger posted thì không tự rollback kiểu xóa dữ liệu.
6. **Lỗi giữa chừng phải recover được**: cần deadline và recovery job.

Nếu học dự án này, nên hiểu theo lớp:

1. Đầu tiên hiểu REST endpoint và response `202`.
2. Sau đó hiểu `payment.created` đi sang Risk.
3. Sau đó hiểu Payment Saga phát command cho Account/Ledger.
4. Cuối cùng mới hiểu Outbox/Inbox/recovery/DLT.

---

## 12. Những gì hiện tại không nên hiểu nhầm

- `202 Accepted` không phải payment succeeded.
- Kafka không đảm bảo end-to-end exactly-once. Thiết kế hiện tại là at-least-once + idempotent processing.
- API Gateway không xử lý nghiệp vụ tiền.
- Risk Service không reserve/capture tiền.
- Account-Ledger Service không quyết định Saga bước tiếp theo; nó xử lý command và phát result.
- Notification Service không quyết định payment/refund thành bại; nó chỉ phản ứng với outcome cuối.
- Ledger journal là immutable fact; không coi ledger như bảng tạm để xóa/sửa khi lỗi.
- `.docs/` là hướng dẫn agent trong quá trình xây dựng; tài liệu bàn giao cho người đọc repo nằm ở `docs/`.

---

## 13. Cách debug một payment cụ thể

Khi có `paymentId`, đi theo thứ tự này:

1. Gọi `GET /api/v1/payments/{paymentId}` xem trạng thái public.
2. Kiểm tra payment row trong Payment DB.
3. Kiểm tra saga row trong `payment_sagas`:
   - current step là gì;
   - status là gì;
   - có `reservationId` chưa;
   - có `journalId` chưa;
   - deadline đã quá hạn chưa.
4. Kiểm tra outbox của Payment Service có event pending/failed không.
5. Nếu đang chờ Risk, kiểm tra Risk assessment/inbox/outbox.
6. Nếu đang chờ Account, kiểm tra Account reservation/capture/release và outbox.
7. Nếu đang chờ Ledger, kiểm tra ledger journal và ledger outbox.
8. Nếu payment/refund đã final mà không có email, kiểm tra Notification inbox/notifications/delivery state.
9. Nếu event bị poison, kiểm tra `payflow.dead-letter.v1` và runbook DLT.

---

## 14. Tóm tắt cực ngắn

Payment happy path:

```text
POST payment
-> Payment lưu payment + saga + outbox payment.created
-> Risk chấm điểm
-> Payment Saga yêu cầu reserve
-> Account reserve tiền
-> Payment Saga yêu cầu ledger post
-> Ledger ghi journal
-> Payment Saga yêu cầu capture
-> Account capture tiền
-> Payment Saga mark succeeded
-> Notification tạo/gửi email
```

Refund happy path:

```text
POST refund
-> Payment giữ refund capacity + outbox refund.requested
-> Ledger ghi refund reversal journal
-> Payment yêu cầu account refund credit
-> Account credit tiền
-> Payment mark refund succeeded
-> Notification tạo/gửi email
```

Cơ chế bảo vệ:

```text
Idempotency chống HTTP retry trùng
Outbox chống mất event DB/Kafka
Inbox chống Kafka duplicate
Saga điều phối nhiều service
Recovery job retry/compensate/manual-review khi quá hạn
```
