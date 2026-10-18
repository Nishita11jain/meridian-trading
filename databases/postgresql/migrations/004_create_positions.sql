CREATE TABLE positions (
    account_id    BIGINT        NOT NULL,
    symbol        VARCHAR(20)   NOT NULL,
    quantity      INT           NOT NULL,
    average_cost  NUMERIC(18,2) NOT NULL,

    CONSTRAINT pk_positions PRIMARY KEY (account_id, symbol),
    CONSTRAINT fk_positions_account
        FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_positions_instrument
        FOREIGN KEY (symbol) REFERENCES instruments (symbol),
    CONSTRAINT ck_positions_quantity_non_negative
        CHECK (quantity >= 0),
    CONSTRAINT ck_positions_average_cost_non_negative
        CHECK (average_cost >= 0)
);