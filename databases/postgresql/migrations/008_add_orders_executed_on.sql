-- Sprint 7: when a fill happened. Written by the Trade Executor's
-- OrderMapper.markFilled(...). NULL until a fill actually occurs.
--
-- Guarded so apply.sh can be safely re-run against a database that already
-- has this column, rather than erroring on "column already exists".

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS executed_on TIMESTAMP;

COMMENT ON COLUMN orders.executed_on IS
    'When the Trade Executor filled this order. NULL until a fill occurs.';