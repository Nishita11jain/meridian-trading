/*
A trade referencing a non-existent order must be rejected.
The order_id is generated so that no matching orders.id row exists.
*/
INSERT INTO trades (
    order_id,
    quantity,
    execution_price
)
VALUES (
    'dddddddd-dddd-dddd-dddd-dddddddddddd'::uuid,
    1,
    100.00
);
