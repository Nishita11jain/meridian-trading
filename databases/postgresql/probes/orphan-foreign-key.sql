/*an order with a non-existent account must be rejected.
The account_id is generated so that no matching accounts.id row exists.
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
VALUES (
    'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee'::uuid,
    999999999,
    'TCS.NS',
    'BUY',
    1,
    100.00,
    'NEW',
    'probe-orphan-fk-001',
    CURRENT_TIMESTAMP
);