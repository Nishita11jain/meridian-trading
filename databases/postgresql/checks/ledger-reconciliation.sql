/*
Ledger reconciliation: every filled order must have exactly one trade row and exactly one ledger row,
and both must match what the order says was executed (a dollar fill at its locked rate). Read-only.

Run:  psql -f database/checks/ledger-reconciliation.sql
A healthy database returns NO ROWS from the first query. Each row it returns is one problem, named in
the "problem" column. The second query is a per-account summary for reading, not a pass/fail test.
*/

-- 1. Problems (expect none)
WITH filled AS (
    SELECT o.id, o.account_id, o.side, o.filled_quantity, o.executed_price,
           -- Cash is in rupees: a dollar fill is converted at the rate locked on the order (fx_rate is NULL for a
           -- rupee order), rounded once to two decimals, exactly as settlement does.
           CASE WHEN o.side = 'BUY' THEN -round(o.executed_price * o.filled_quantity * COALESCE(o.fx_rate, 1), 2)
                ELSE round(o.executed_price * o.filled_quantity * COALESCE(o.fx_rate, 1), 2) END AS expected_amount
    FROM orders o
    WHERE o.status = 'FILLED'
),
trade_rows AS (
    SELECT order_id, count(*) AS n, max(quantity) AS quantity, max(execution_price) AS price
    FROM trades GROUP BY order_id
),
ledger_rows AS (
    SELECT reference_id, count(*) AS n, sum(amount) AS amount, max(transaction_type) AS type,
           max(account_id) AS account_id
    FROM account_transactions WHERE reference_type = 'ORDER' GROUP BY reference_id
)
SELECT f.id AS order_id, f.account_id, problem
FROM filled f
LEFT JOIN trade_rows t ON t.order_id = f.id
LEFT JOIN ledger_rows l ON l.reference_id = f.id::text
CROSS JOIN LATERAL (VALUES
    (CASE WHEN t.order_id IS NULL THEN 'no trade row' END),
    (CASE WHEN t.n > 1 THEN 'more than one trade row' END),
    (CASE WHEN t.order_id IS NOT NULL AND (t.quantity <> f.filled_quantity OR t.price <> f.executed_price)
          THEN 'trade row differs from the order' END),
    (CASE WHEN l.reference_id IS NULL THEN 'no ledger row' END),
    (CASE WHEN l.n > 1 THEN 'more than one ledger row' END),
    (CASE WHEN l.reference_id IS NOT NULL AND l.amount <> f.expected_amount THEN 'ledger amount differs from the fill' END),
    (CASE WHEN l.reference_id IS NOT NULL AND l.type <> f.side THEN 'ledger type differs from the side' END),
    (CASE WHEN l.reference_id IS NOT NULL AND l.account_id <> f.account_id THEN 'ledger row on another account' END)
) AS checks(problem)
WHERE problem IS NOT NULL
UNION ALL
-- Ledger rows that point at an order that is not filled (money moved for nothing)
SELECT o.id, l.account_id, 'ledger row for an order that is not FILLED (' || o.status || ')'
FROM account_transactions l
JOIN orders o ON l.reference_type = 'ORDER' AND o.id::text = l.reference_id
WHERE o.status <> 'FILLED'
ORDER BY 2, 1;

-- 2. Per-account summary
SELECT a.id AS account,
       a.cash_balance,
       count(l.id) FILTER (WHERE l.reference_type = 'ORDER') AS trade_movements,
       COALESCE(sum(l.amount) FILTER (WHERE l.reference_type = 'ORDER'), 0) AS net_from_trades,
       COALESCE(sum(l.amount) FILTER (WHERE l.transaction_type IN ('DEPOSIT', 'WITHDRAWAL')), 0) AS deposits_and_withdrawals
FROM accounts a
LEFT JOIN account_transactions l ON l.account_id = a.id
GROUP BY a.id, a.cash_balance
ORDER BY a.id;
