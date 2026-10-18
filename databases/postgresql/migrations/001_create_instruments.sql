CREATE TABLE instruments (
    symbol       VARCHAR(20)  NOT NULL,
    instrument_name         VARCHAR(255) NOT NULL,
    asset_class  VARCHAR(20)  NOT NULL,
    currency     CHAR(3)      NOT NULL,
    tradable     BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT pk_instruments PRIMARY KEY (symbol),
    CONSTRAINT ck_instruments_asset_class
        CHECK (asset_class IN ('EQUITY', 'ETF', 'FX', 'CRYPTO', 'BOND')),
    CONSTRAINT ck_instruments_currency
        CHECK (currency ~ '^[A-Z]{3}$')
);