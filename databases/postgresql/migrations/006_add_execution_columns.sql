ALTER TABLE positions
    ADD COLUMN version INT NOT NULL DEFAULT 0;

ALTER TABLE orders
    ADD COLUMN executed_price   NUMERIC(18,2),
    ADD COLUMN filled_quantity  INT,
    ADD COLUMN updated_on       TIMESTAMP;

ALTER TABLE orders
    ADD CONSTRAINT ck_orders_executed_price_positive
        CHECK (executed_price IS NULL OR executed_price > 0),
    ADD CONSTRAINT ck_orders_filled_quantity_non_negative
        CHECK (filled_quantity IS NULL OR filled_quantity >= 0);

COMMENT ON COLUMN positions.version IS
    'Optimistic lock, same pattern as accounts.version -- both the Trade REST API and the Trade Executor can write to the same position row.';
COMMENT ON COLUMN orders.executed_price IS
    'Price the Trade Executor actually filled at. NULL until a fill occurs.';
COMMENT ON COLUMN orders.filled_quantity IS
    'Units actually filled so far. NULL until at least one fill occurs.';
COMMENT ON COLUMN orders.updated_on IS
    'Last time this order row changed, for any reason.';