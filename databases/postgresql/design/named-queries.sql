-- ============================================================
-- SPRINT 3 - SIX NAMED QUERIES
-- ============================================================


-- ============================================================
-- QUERY 1
-- Open orders for one account, newest first.
--
-- Example account: ETP-4102
-- ============================================================

SELECT
    o.id,
    a.account_id AS client_account,
    o.symbol,
    o.side,
    o.quantity,
    o.price,
    o.status,
    o.created_on
FROM orders o
JOIN accounts a
    ON a.id = o.account_id
WHERE a.account_id = 'ETP-4102'
  AND o.status = 'NEW'
ORDER BY o.created_on DESC;


-- ============================================================
-- QUERY 2
-- Last 50 orders for one account, any state, newest first.
--
-- Example account: ETP-4101
-- ============================================================

SELECT
    o.id,
    a.account_id AS client_account,
    o.symbol,
    o.side,
    o.quantity,
    o.price,
    o.status,
    o.created_on
FROM orders o
JOIN accounts a
    ON a.id = o.account_id
WHERE a.account_id = 'ETP-4101'
ORDER BY o.created_on DESC
LIMIT 50;


-- ============================================================
-- QUERY 3
-- Everything one account currently holds.
--
-- Example account: ETP-4101
-- ============================================================

SELECT
    a.account_id AS client_account,
    p.symbol,
    p.quantity,
    p.average_cost
FROM positions p
JOIN accounts a
    ON a.id = p.account_id
WHERE a.account_id = 'ETP-4101'
ORDER BY p.symbol;


-- ============================================================
-- QUERY 4
-- Every order since a timestamp across all accounts.
--
-- Example timestamp: 2026-08-01 00:00:00
-- ============================================================

SELECT
    o.id,
    a.account_id AS client_account,
    o.symbol,
    o.side,
    o.quantity,
    o.price,
    o.status,
    o.created_on
FROM orders o
JOIN accounts a
    ON a.id = o.account_id
WHERE o.created_on >= TIMESTAMP '2026-08-01 00:00:00'
ORDER BY o.created_on ASC;


-- ============================================================
-- QUERY 5
-- Resolve an account from its customer-facing account reference.
--
-- Example: ETP-4101
-- ============================================================

SELECT
    id,
    account_id,
    holder_name,
    cash_balance,
    status,
    version,
    last_updated
FROM accounts
WHERE account_id = 'ETP-4101';



-- ============================================================
-- QUERY 6
-- Filled orders for one account, oldest first.
--
-- Also calculate:
--   1. Running cash committed
--   2. Rank by order value within the instrument
--
-- Cash committed is the order value for BUY orders.
--
-- Example account: ETP-4101
-- ============================================================

WITH filled_orders AS (
    SELECT
        o.id,
        a.account_id AS client_account,
        o.symbol,
        o.side,
        o.quantity,
        o.price,
        o.created_on,

        (o.quantity * o.price)::NUMERIC(18,2)
            AS order_value,

        CASE
            WHEN o.side = 'BUY'
                THEN (o.quantity * o.price)::NUMERIC(18,2)
            ELSE
                0::NUMERIC(18,2)
        END AS cash_committed

    FROM orders o
    JOIN accounts a
        ON a.id = o.account_id

    WHERE a.account_id = 'ETP-4101'
      AND o.status = 'FILLED'
)

SELECT
    id,
    client_account,
    symbol,
    side,
    quantity,
    price,
    order_value,
    created_on,

    SUM(cash_committed) OVER (
        ORDER BY created_on, id
        ROWS BETWEEN UNBOUNDED PRECEDING
        AND CURRENT ROW
    ) AS running_cash_committed,

    RANK() OVER (
        PARTITION BY symbol
        ORDER BY order_value DESC
    ) AS value_rank_within_instrument

FROM filled_orders
ORDER BY created_on, id;