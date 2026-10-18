-- Trade Advice & Signals (Sprint 10 stretch extension). A read-only module
-- inside the Trade REST API: it informs a customer who trades, it never
-- trades. No background job polls Fauxnance for every instrument -- a
-- signal is only ever fetched and computed on an explicit customer action
-- ("Analyse now"), and the result is cached so repeated clicks inside the
-- cooldown window never call out again.
--
-- Both tables are keyed by symbol alone, not by account: a signal is a
-- property of the instrument, shared by every customer who holds or
-- watches it, exactly like market_price (migration 009) is shared rather
-- than duplicated per account.
--
-- CANDLE_CACHE holds the raw Fauxnance candle response behind the computed
-- signal, so a signal's numbers can be traced back to what produced them.
-- expires_at is the TTL the module can state out loud, not an implicit
-- assumption: a request past it refetches, a request inside it is served
-- from this table with no external call.
--
-- TRADE_SIGNAL holds the computed answer: a direction, a strength and the
-- sentence that explains it. computed_at and expires_at mirror
-- candle_cache's own timestamps so the UI can show "as of" without a join.
--
-- This migration is written to be safely re-runnable, like migration 010.

CREATE TABLE IF NOT EXISTS candle_cache (
    symbol       VARCHAR(20)   NOT NULL,
    interval     VARCHAR(10)   NOT NULL DEFAULT '1d',
    currency     CHAR(3),
    candles_json TEXT          NOT NULL,
    fetched_at   TIMESTAMP     NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMP     NOT NULL,

    CONSTRAINT pk_candle_cache PRIMARY KEY (symbol)
);

CREATE TABLE IF NOT EXISTS trade_signal (
    symbol       VARCHAR(20)   NOT NULL,
    direction    VARCHAR(4)    NOT NULL,
    strength     NUMERIC(6,4)  NOT NULL,
    rationale    VARCHAR(500)  NOT NULL,
    computed_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMP     NOT NULL,

    CONSTRAINT pk_trade_signal PRIMARY KEY (symbol),
    CONSTRAINT ck_trade_signal_direction
        CHECK (direction IN ('BUY', 'SELL', 'HOLD'))
);

COMMENT ON TABLE candle_cache IS
    'Raw Fauxnance candle response per symbol, fetched only on an explicit Analyse-now action. expires_at is the stated cache TTL.';
COMMENT ON TABLE trade_signal IS
    'The latest computed BUY/SELL/HOLD signal per symbol: direction, strength and the sentence explaining it, from an SMA20/SMA50 crossover over candle_cache.';
