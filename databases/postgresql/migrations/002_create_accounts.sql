CREATE TABLE accounts (
    id            BIGINT        GENERATED ALWAYS AS IDENTITY,
    account_id    VARCHAR(32)   NOT NULL,
    holder_name   VARCHAR(255)  NOT NULL,
    cash_balance  NUMERIC(18,2) NOT NULL,
    status        VARCHAR(20)   NOT NULL,
    version       INT           NOT NULL DEFAULT 0,
    last_updated  TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT uq_accounts_account_id UNIQUE (account_id),
    CONSTRAINT ck_accounts_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_accounts_cash_balance_non_negative
        CHECK (cash_balance >= 0)
);