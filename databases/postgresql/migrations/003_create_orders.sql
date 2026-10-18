CREATE TABLE orders (
    id               UUID          NOT NULL,
    account_id       BIGINT        NOT NULL,
    symbol           VARCHAR(20)   NOT NULL,
    side             VARCHAR(4)    NOT NULL,
    quantity         INT           NOT NULL,
    price            NUMERIC(18,2) NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    idempotency_key  VARCHAR(100)  NOT NULL,
    created_on       TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT fk_orders_account
        FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_orders_instrument
        FOREIGN KEY (symbol) REFERENCES instruments (symbol),
    CONSTRAINT uq_orders_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_orders_side
        CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_orders_status
        CHECK (status IN ('NEW', 'FILLED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_orders_quantity_positive
        CHECK (quantity > 0),
    CONSTRAINT ck_orders_price_positive
        CHECK (price > 0)
);