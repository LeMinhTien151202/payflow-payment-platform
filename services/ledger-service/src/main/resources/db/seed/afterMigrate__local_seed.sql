INSERT INTO ledger.ledger_accounts(id,owner_type,owner_id,currency) VALUES
 ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','MERCHANT','11111111-1111-4111-8111-111111111111','VND'),
 ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','CUSTOMER_ACCOUNT','3beff442-7f10-4504-aab4-12d985cf3e95','VND'),
 ('cccccccc-cccc-4ccc-8ccc-cccccccccccc','CUSTOMER_ACCOUNT','039bedb6-b2d6-47df-aa25-2035e39136a3','VND'),
 ('dddddddd-dddd-4ddd-8ddd-dddddddddddd','CUSTOMER_ACCOUNT','44444444-4444-4444-8444-444444444444','VND'),
 ('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee','CUSTOMER_ACCOUNT','55555555-5555-4555-8555-555555555555','VND')
ON CONFLICT(id) DO NOTHING;
