# `ledger.post-payment.requested` v1

| Thuộc tính | Giá trị |
| --- | --- |
| Owner/producer | `payment-service` |
| Consumer chính | Ledger boundary |
| Topic | `payflow.payment.events.v1` |
| Kafka key | `paymentId` |
| Event type/version | `ledger.post-payment.requested` / `1` |
| Aggregate type | `PAYMENT` |

Payload gồm `paymentId`, `customerId`, `merchantId`, positive `amount` scale 4 và uppercase
three-letter `currency`. Payment tạo command này chỉ sau `account.funds-reserved` khớp và đồng thời
chuyển `RESERVING_FUNDS → PROCESSING` trong local transaction.

Ledger tự resolve ledger-account theo owner references; Payment không gửi Ledger-owned account ID và
không đọc Ledger database. Contract v1 chưa mang fee snapshot: ADR-019 yêu cầu phát hành version mới
trước khi tạo posting có fee. Ledger có thể kiểm tra/ghi gross balanced journal core, nhưng không được tự đọc fee hiện
tại rồi áp vào payment lịch sử.
