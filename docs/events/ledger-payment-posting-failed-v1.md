# `ledger.payment-posting-failed` v1

| Thuộc tính | Giá trị |
| --- | --- |
| Owner/producer | Ledger boundary |
| Consumer chính | `payment-service` |
| Topic | `payflow.ledger.events.v1` |
| Kafka key | `paymentId` |
| Event type/version | `ledger.payment-posting-failed` / `1` |
| Aggregate type | `PAYMENT` |

Payload v1 gồm `paymentId`, stable uppercase `failureCode` và `failedAt`. Raw exception/message
không được đưa vào contract.

Payment có thể retry hữu hạn khi lỗi được phân loại retryable. Khi không còn retry và chưa có journal
fact, Saga phát `account.release.requested`; outcome mơ hồ hoặc đã có journal fact phải vào manual
review, không tự release.

