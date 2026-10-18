CREATE INDEX ix_orders_account_open_created
    ON orders (account_id, created_on DESC)
    WHERE status = 'NEW';

CREATE INDEX ix_orders_account_created
    ON orders (account_id, created_on DESC);

CREATE INDEX ix_orders_created_on
    ON orders (created_on);