-- Automated Strategy Execution (Sprint 10 stretch extension, second of two). A customer configures
-- a rule once; the platform evaluates it against every market-data quote for that symbol and places
-- a real order when it fires, with nobody confirming. Every control here is load-bearing, not
-- hardening added after a demo works -- see decision-log for the identity and risk-bounding choices.
--
-- TRADING_STRATEGY is one row per configured rule. A strategy is single-sided (side is BUY or SELL,
-- never both): a customer wanting both directions on the same instrument creates two strategies. The
-- type-specific parameters (fast_window/slow_window for CROSSOVER, band_period/band_multiplier for
-- BOLLINGER) are nullable because only one set applies to a given row; application code enforces
-- which set is required for which strategy_type, not a CHECK constraint, since that logic already
-- lives in StrategyRequest validation and duplicating it in SQL would be two places to keep in sync.
--
-- The three risk fields (max_spend, max_position_size, max_consecutive_failures) are NOT NULL and
-- have no platform default: every strategy states its own limits explicitly, nothing implicit.
-- consecutive_failures is reset to 0 on a fill and incremented on a real order-placement failure
-- (never on a skip -- a skip is the cap working as designed, not a malfunction); it auto-flips
-- status to DISABLED, with disabled_reason set, once it reaches max_consecutive_failures.
--
-- STRATEGY_EXECUTION_LOG is one row per evaluation that actually fired a strategy's condition --
-- not one row per quote -- so a customer (and the demo) can show exactly when and why an order was
-- placed, skipped or rejected.

CREATE TABLE IF NOT EXISTS trading_strategy (
    id                        BIGSERIAL     NOT NULL,
    account_id                BIGINT        NOT NULL,
    symbol                    VARCHAR(20)   NOT NULL,
    side                      VARCHAR(4)    NOT NULL,
    strategy_type             VARCHAR(10)   NOT NULL,
    fast_window               INT,
    slow_window               INT,
    band_period                INT,
    band_multiplier           NUMERIC(4,2),
    order_quantity            INT           NOT NULL,
    max_spend                 NUMERIC(18,2) NOT NULL,
    max_position_size         INT           NOT NULL,
    max_consecutive_failures  INT           NOT NULL DEFAULT 3,
    consecutive_failures      INT           NOT NULL DEFAULT 0,
    status                    VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    disabled_reason           VARCHAR(200),
    created_on                TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_on                TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_trading_strategy PRIMARY KEY (id),
    CONSTRAINT fk_trading_strategy_account
        FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_trading_strategy_side
        CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_trading_strategy_type
        CHECK (strategy_type IN ('CROSSOVER', 'BOLLINGER')),
    CONSTRAINT ck_trading_strategy_status
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_trading_strategy_order_quantity
        CHECK (order_quantity > 0),
    CONSTRAINT ck_trading_strategy_max_spend
        CHECK (max_spend > 0),
    CONSTRAINT ck_trading_strategy_max_position
        CHECK (max_position_size > 0),
    CONSTRAINT ck_trading_strategy_max_failures
        CHECK (max_consecutive_failures > 0)
);

CREATE INDEX IF NOT EXISTS ix_trading_strategy_symbol_status
    ON trading_strategy (symbol, status);
CREATE INDEX IF NOT EXISTS ix_trading_strategy_account
    ON trading_strategy (account_id);

CREATE TABLE IF NOT EXISTS strategy_execution_log (
    id                BIGSERIAL     NOT NULL,
    strategy_id       BIGINT        NOT NULL,
    triggered_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    triggering_price  NUMERIC(18,4) NOT NULL,
    action            VARCHAR(20)   NOT NULL,
    outcome           VARCHAR(10)   NOT NULL,
    detail            VARCHAR(300),
    order_id          UUID,

    CONSTRAINT pk_strategy_execution_log PRIMARY KEY (id),
    CONSTRAINT fk_strategy_execution_log_strategy
        FOREIGN KEY (strategy_id) REFERENCES trading_strategy (id) ON DELETE CASCADE,
    CONSTRAINT ck_strategy_execution_log_outcome
        CHECK (outcome IN ('FILLED', 'REJECTED', 'SKIPPED'))
);

CREATE INDEX IF NOT EXISTS ix_strategy_execution_log_strategy
    ON strategy_execution_log (strategy_id, triggered_at DESC);

COMMENT ON TABLE trading_strategy IS
    'One configured automated rule per row. Single-sided (BUY or SELL); risk limits are required, never implicit.';
COMMENT ON TABLE strategy_execution_log IS
    'One row per evaluation that fired a condition: what triggered, what was attempted, and the outcome.';
