/*
Duplicate idempotency key must be rejected.
The idempotency key below already exists in the seeded orders data.
*/
INSERT INTO orders (
    id,
    account_id,
    symbol,
    side,
    quantity,
    price,
    status,
    idempotency_key,
    created_on
)
SELECT
    'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid,
    account_id,
    'TCS.NS',
    'BUY',
    1,
    100.00,
    'NEW',
    idempotency_key,
    CURRENT_TIMESTAMP
FROM orders
WHERE idempotency_key = 'seed-4101-tcs-001';