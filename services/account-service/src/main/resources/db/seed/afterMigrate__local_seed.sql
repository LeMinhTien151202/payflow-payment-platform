INSERT INTO account.accounts (id,currency,available_balance,reserved_balance,status,version) VALUES
 ('039bedb6-b2d6-47df-aa25-2035e39136a3','VND',1000000.0000,0.0000,'ACTIVE',0),
 ('55555555-5555-4555-8555-555555555555','VND',100000.0000,0.0000,'ACTIVE',0)
ON CONFLICT (id) DO NOTHING;
