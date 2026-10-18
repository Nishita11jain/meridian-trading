/*
A second trade for an order that already has one must be rejected.
Partial fills are out of scope, so an order corresponds to at most one
trade (uq_trades_order_id). This probe inserts against the FILLED seed
order 20000000-0000-0000-0000-000000000001, which already has a trade
row from seed/seed.sql.
*/
INSERT INTO trades (
    order_id,
    quantity,
    execution_price
)
VALUES (
    '20000000-0000-0000-0000-000000000001'::uuid,
    100,
    3425.00
);
