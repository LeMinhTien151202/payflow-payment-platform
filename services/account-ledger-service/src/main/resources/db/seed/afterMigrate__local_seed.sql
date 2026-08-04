-- Các dữ liệu giả lập (fixtures) dành riêng cho local cho các kịch bản portfolio Phase 1B.
--
-- Callback này của Flyway chỉ được nạp duy nhất bởi Spring profile `local`. Mỗi câu lệnh insert đều có tính idempotent và
-- cố ý sử dụng ON CONFLICT DO NOTHING: việc khởi động lại ứng dụng tuyệt đối không bao giờ được tự bổ sung tiền hoặc
-- ghi đè lên số dư mà lập trình viên đã thay đổi. Để quay về trạng thái ban đầu như tài liệu, hãy xóa
-- các volume Compose một cách thủ công và để migration tự dựng lại.

-- Source account là nguồn sự thật (source of truth) cho số dư vận hành. Tài khoản cho kịch bản happy-path bắt đầu với
-- 1.000.000 VND; tài khoản thứ hai phục vụ việc tái hiện kịch bản không đủ tiền (insufficient-funds).
INSERT INTO account.accounts (
    id, currency, available_balance, reserved_balance, status, version)
VALUES
    ('039bedb6-b2d6-47df-aa25-2035e39136a3', 'VND', 1000000.0000, 0.0000, 'ACTIVE', 0),
    ('55555555-5555-4555-8555-555555555555', 'VND',  100000.0000, 0.0000, 'ACTIVE', 0)
ON CONFLICT (id) DO NOTHING;

-- Các ánh xạ Ledger cố ý tách biệt với các tài khoản vận hành. Việc ghi nhận payment (payment posting) hiện tại
-- xác định phía khách hàng bằng customerId, trong khi việc ghi nhận refund xác định nó bằng sourceAccountId;
-- cả hai mapping do đó đều cần thiết để cùng một workflow local có thể post và refund an toàn sau đó.
INSERT INTO ledger.ledger_accounts (id, owner_type, owner_id, currency)
VALUES
    ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'MERCHANT',
     '11111111-1111-4111-8111-111111111111', 'VND'),
    ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'CUSTOMER_ACCOUNT',
     '3beff442-7f10-4504-aab4-12d985cf3e95', 'VND'),
    ('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'CUSTOMER_ACCOUNT',
     '039bedb6-b2d6-47df-aa25-2035e39136a3', 'VND'),
    ('dddddddd-dddd-4ddd-8ddd-dddddddddddd', 'CUSTOMER_ACCOUNT',
     '44444444-4444-4444-8444-444444444444', 'VND'),
    ('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', 'CUSTOMER_ACCOUNT',
     '55555555-5555-4555-8555-555555555555', 'VND')
ON CONFLICT (id) DO NOTHING;
