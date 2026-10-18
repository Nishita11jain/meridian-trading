-- The exchange rate locked on an order when it is placed, so cash is reserved and settled in rupees at one agreed
-- rate. NULL means the order is in rupees (rate 1), which is also how every order placed before this migration reads.
-- A trade keeps both the instrument's own price and the rate, so the rupee value can always be recomputed.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS fx_rate NUMERIC(18, 6);
ALTER TABLE trades ADD COLUMN IF NOT EXISTS fx_rate NUMERIC(18, 6);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_orders_fx_rate_positive') THEN
        ALTER TABLE orders ADD CONSTRAINT ck_orders_fx_rate_positive CHECK (fx_rate IS NULL OR fx_rate > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_trades_fx_rate_positive') THEN
        ALTER TABLE trades ADD CONSTRAINT ck_trades_fx_rate_positive CHECK (fx_rate IS NULL OR fx_rate > 0);
    END IF;
END $$;

COMMENT ON COLUMN orders.fx_rate IS 'Rupees per unit of the instrument currency, locked at placement (with margin); NULL = rupee order.';
COMMENT ON COLUMN trades.fx_rate IS 'Rate used to convert execution_price into rupees; NULL = rupee trade.';
