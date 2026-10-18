-- Sprint 10: backfill the execution record and the cash ledger for fills that never got them.
--
-- trades and account_transactions (migration 007) were only ever written by the seed. The Trade
-- Executor settled every later fill (moving cash and position) but wrote neither table, so fills made
-- through the platform since July had no trade row and no ledger row. The executor now writes both in
-- its settlement transaction; this migration adds the missing rows for fills that settled before that.
--
-- Rows are only ADDED, never changed or deleted: each FILLED order that has no trade row gets one, and
-- each FILLED order that has no ledger row gets one. Values come from what settlement actually applied:
--   trades:                order, filled quantity, executed price, executed time
--   account_transactions:  BUY = -(executed price x filled quantity), SELL = +(the same),
--                          reference ORDER / the order id, at the executed time
-- This is exactly the cash delta the executor applied to accounts.cash_balance (2-dp price x whole
-- quantity, so no rounding is involved).
--
-- Not added: opening-balance (DEPOSIT) rows. The accounts' starting cash is not recorded anywhere, so
-- inventing it would put an unverified number in an audit trail. The ledger therefore records every
-- trade's cash movement, but not the money each account started with.
--
-- Safely re-runnable: NOT EXISTS guards make a second run add nothing.

INSERT INTO trades (order_id, quantity, execution_price, executed_at)
SELECT o.id,
       o.filled_quantity,
       o.executed_price,
       COALESCE(o.executed_on, o.updated_on, o.created_on)
FROM orders o
WHERE o.status = 'FILLED'
  AND o.executed_price IS NOT NULL
  AND o.filled_quantity > 0
  AND NOT EXISTS (SELECT 1 FROM trades t WHERE t.order_id = o.id);

INSERT INTO account_transactions (account_id, transaction_type, amount, reference_type, reference_id, created_at)
SELECT o.account_id,
       o.side,
       CASE WHEN o.side = 'BUY' THEN -(o.executed_price * o.filled_quantity)
            ELSE o.executed_price * o.filled_quantity END,
       'ORDER',
       o.id::text,
       COALESCE(o.executed_on, o.updated_on, o.created_on)
FROM orders o
WHERE o.status = 'FILLED'
  AND o.executed_price IS NOT NULL
  AND o.filled_quantity > 0
  AND NOT EXISTS (
      SELECT 1 FROM account_transactions t
      WHERE t.reference_type = 'ORDER' AND t.reference_id = o.id::text
  );
