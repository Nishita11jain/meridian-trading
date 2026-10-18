-- Sprint 10: exchange holidays, for the trading-hours rule.
--
-- Orders are refused while an instrument's market is closed (decision log 0017). Weekends and session
-- hours are fixed in code (NSE/BSE 09:15-15:30 IST, US 09:30-16:00 New York, FX Sunday 17:00 to Friday
-- 17:00 New York, crypto always open). Holidays change every year, so they are data: one row per
-- market and date on which that market does not trade. Both the Trade API (refusing at placement and
-- showing Open/Closed) and the executor (refusing at settlement) read this table.
--
-- market        NSE, BSE, US or FX (crypto has no holidays)
-- holiday_date  the exchange-local date the market is closed
-- name          what the holiday is, for whoever maintains the list
--
-- The table starts empty: until dates are added, only weekends and session hours apply. A new table
-- only; no existing table or row changes. Safely re-runnable.

CREATE TABLE IF NOT EXISTS market_holidays (
    market        VARCHAR(10)   NOT NULL,
    holiday_date  DATE          NOT NULL,
    name          VARCHAR(100)  NOT NULL,
    created_on    TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_market_holidays PRIMARY KEY (market, holiday_date),
    CONSTRAINT ck_market_holidays_market CHECK (market IN ('NSE', 'BSE', 'US', 'FX'))
);

COMMENT ON TABLE market_holidays IS
    'Exchange-local dates on which a market does not trade. Read by the trading-hours rule in the Trade API and the executor.';

-- Example (not inserted here):
-- INSERT INTO market_holidays (market, holiday_date, name) VALUES ('NSE', '2026-10-02', 'Gandhi Jayanti');
