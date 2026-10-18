-- Daily exchange rates, written by the executor's FxRatePoller and read by the portfolio module to express a
-- mixed-currency portfolio in one base currency (INR). Fauxnance updates its FX pairs once a day, so there is one
-- row per pair per UTC day. Only the two pairs the platform needs are allowed.
CREATE TABLE IF NOT EXISTS fx_rates (
    pair        VARCHAR(10)    NOT NULL,
    rate_date   DATE           NOT NULL,
    rate        NUMERIC(18, 6) NOT NULL,
    quote_as_of TIMESTAMP      NOT NULL,
    fetched_on  TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_fx_rates PRIMARY KEY (pair, rate_date),
    CONSTRAINT ck_fx_rates_pair CHECK (pair IN ('USDINR', 'INRUSD')),
    CONSTRAINT ck_fx_rates_rate_positive CHECK (rate > 0)
);

CREATE INDEX IF NOT EXISTS ix_fx_rates_pair_date ON fx_rates (pair, rate_date DESC);

COMMENT ON TABLE fx_rates IS 'Daily USD/INR and INR/USD rates from Fauxnance; read-only for the portfolio module.';
