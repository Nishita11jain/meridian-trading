-- Schema for the Sprint 10 (Watchlists and Price Alerts) extension, added
-- early during the Sprint 3 database work since these tables are pure
-- additions with no dependency on any Sprint-10 code: nothing before
-- Sprint 10 reads or writes them. These are read models built entirely from
-- the market-data topic (contracts/event-schemas/kafka-topics.md) -- nothing here is
-- written by the Trade REST API or the Trade Executor's own order-placement
-- or settlement paths. A consumer in group `watchlist-service` (per the
-- producer/consumer matrix in that contract) owns writing all five tables;
-- that consumer, and the API/UI around it, is Sprint 10's own work.
--
-- MARKET_PRICE is the latest observed quote per symbol: one row per symbol,
-- upserted on every QUOTE event. It exists so a consumer can answer "what is
-- AAPL trading at right now" without scanning history.
--
-- PRICE_HISTORY is the append-only tick log: one row per QUOTE event
-- consumed, at the poller's existing per-symbol cadence (no downsampling
-- here; a coarser view is a query-time concern, not a write-time one).
--
-- WATCHLISTS and WATCHLIST_ITEMS let an account keep more than one named
-- list of symbols. uq_watchlists_account_name stops an account from
-- creating two lists with the same name; it does not stop the same symbol
-- appearing on two different lists.
--
-- PRICE_ALERTS carries a status so a fired alert does not refire on every
-- subsequent tick that still crosses the threshold: a consumer only ever
-- acts on an alert while it is ACTIVE, and flips it to TRIGGERED (recording
-- when and at what price) in the same transaction as the notification it
-- sends, mirroring the guarded-state-transition idempotency pattern the
-- Trade Executor already uses for orders.
--
-- This migration is written to be safely re-runnable: every CREATE and
-- every index is guarded, so running apply.sh again on a database that
-- already has this migration applied is a no-op here, not an error.

CREATE TABLE IF NOT EXISTS market_price (
    symbol         VARCHAR(20)   NOT NULL,
    last_price     NUMERIC(18,4) NOT NULL,
    bid            NUMERIC(18,4),
    ask            NUMERIC(18,4),
    currency       CHAR(3)       NOT NULL,
    change         NUMERIC(18,4),
    change_percent NUMERIC(9,4),
    previous_close NUMERIC(18,4),
    market_state   VARCHAR(10)   NOT NULL DEFAULT 'unknown',
    stale          BOOLEAN       NOT NULL DEFAULT FALSE,
    quote_as_of    TIMESTAMP     NOT NULL,
    updated_on     TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_market_price PRIMARY KEY (symbol),
    CONSTRAINT fk_market_price_instrument
        FOREIGN KEY (symbol) REFERENCES instruments (symbol),
    CONSTRAINT ck_market_price_market_state
        CHECK (market_state IN ('open', 'closed', 'pre', 'post', 'unknown')),
    CONSTRAINT ck_market_price_currency
        CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE IF NOT EXISTS price_history (
    id           BIGINT        GENERATED ALWAYS AS IDENTITY,
    symbol       VARCHAR(20)   NOT NULL,
    price        NUMERIC(18,4) NOT NULL,
    bid          NUMERIC(18,4),
    ask          NUMERIC(18,4),
    quote_as_of  TIMESTAMP     NOT NULL,
    recorded_on  TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_price_history PRIMARY KEY (id),
    CONSTRAINT fk_price_history_instrument
        FOREIGN KEY (symbol) REFERENCES instruments (symbol)
);

CREATE INDEX IF NOT EXISTS ix_price_history_symbol_quote_as_of
    ON price_history (symbol, quote_as_of DESC);

CREATE TABLE IF NOT EXISTS watchlists (
    id          BIGINT        GENERATED ALWAYS AS IDENTITY,
    account_id  BIGINT        NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    created_on  TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_watchlists PRIMARY KEY (id),
    CONSTRAINT fk_watchlists_account
        FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT uq_watchlists_account_name UNIQUE (account_id, name)
);

CREATE TABLE IF NOT EXISTS watchlist_items (
    watchlist_id  BIGINT       NOT NULL,
    symbol        VARCHAR(20)  NOT NULL,
    added_on      TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_watchlist_items PRIMARY KEY (watchlist_id, symbol),
    CONSTRAINT fk_watchlist_items_watchlist
        FOREIGN KEY (watchlist_id) REFERENCES watchlists (id),
    CONSTRAINT fk_watchlist_items_instrument
        FOREIGN KEY (symbol) REFERENCES instruments (symbol)
);

CREATE TABLE IF NOT EXISTS price_alerts (
    id             BIGINT        GENERATED ALWAYS AS IDENTITY,
    account_id     BIGINT        NOT NULL,
    symbol         VARCHAR(20)   NOT NULL,
    direction      VARCHAR(5)    NOT NULL,
    target_price   NUMERIC(18,4) NOT NULL,
    status         VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    created_on     TIMESTAMP     NOT NULL DEFAULT NOW(),
    triggered_on   TIMESTAMP,
    triggered_price NUMERIC(18,4),

    CONSTRAINT pk_price_alerts PRIMARY KEY (id),
    CONSTRAINT fk_price_alerts_account
        FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_price_alerts_instrument
        FOREIGN KEY (symbol) REFERENCES instruments (symbol),
    CONSTRAINT ck_price_alerts_direction
        CHECK (direction IN ('ABOVE', 'BELOW')),
    CONSTRAINT ck_price_alerts_status
        CHECK (status IN ('ACTIVE', 'TRIGGERED', 'CANCELLED')),
    CONSTRAINT ck_price_alerts_target_price_positive
        CHECK (target_price > 0),
    CONSTRAINT ck_price_alerts_triggered_fields
        CHECK (
            (status = 'TRIGGERED' AND triggered_on IS NOT NULL AND triggered_price IS NOT NULL)
            OR (status <> 'TRIGGERED')
        )
);

CREATE INDEX IF NOT EXISTS ix_price_alerts_symbol_active
    ON price_alerts (symbol) WHERE status = 'ACTIVE';

COMMENT ON TABLE market_price IS
    'Latest observed quote per symbol. One row per symbol, upserted on every QUOTE event from market-data.';
COMMENT ON TABLE price_history IS
    'Append-only tick log. One row per QUOTE event consumed, at the poller''s existing per-symbol cadence.';
COMMENT ON TABLE watchlists IS
    'A named list of symbols an account is tracking. An account may have more than one.';
COMMENT ON TABLE watchlist_items IS
    'Symbols on a watchlist.';
COMMENT ON TABLE price_alerts IS
    'A threshold an account wants notified on. status stays ACTIVE until a consumer fires it exactly once and flips it to TRIGGERED.';
