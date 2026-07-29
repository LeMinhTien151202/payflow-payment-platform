# `refund.requested` v1

| Thuộc tính | Giá trị |
| --- | --- |
| Owner/producer | `payment-service` |
| Consumer chính | Account refund workflow (chưa nối runtime) |
| Topic | `payflow.refund.events.v1` |
| Kafka key | `paymentId` |
| Event type/version | `refund.requested` / `1` |
| Aggregate type | `PAYMENT` |

Event được append sau khi Payment Service đã khóa payment và commit cùng lúc bốn fact: refundable
capacity được giữ, Refund `CREATED`, idempotent response và outbox row. Đây không phải bằng chứng rằng
tiền đã được credit hoặc ledger reversal đã posted.

Payload chính xác gồm:

- `refundId`, `paymentId`, `merchantId`, `customerId`, `accountId`;
- positive `amount` scale 4 và `currency` uppercase;
- `requestedAt` UTC.

`reason` và JWT actor chỉ nằm trong database Payment để audit; không truyền qua Kafka hoặc log.
Mọi refund của cùng payment dùng `paymentId` làm key để giữ ordering. Consumer phải dùng
`(eventId, consumerName)` inbox và business reference theo `refundId`; duplicate không được credit hai
lần. Retry/offset/DLT chưa được coi verified cho đến khi chạy Kafka/PostgreSQL integration test.
