-- Sprint 7+: execution facts and cash-movement audit trail.
--
-- TRADES records what was actually executed against an order (distinct from
-- ORDERS, which records what the customer requested). One order corresponds
-- to at most one trade because partial fills are out of scope for this
-- platform (see sprint-07-event-backbone: "an unmarketable order is rejected
-- rather than rested"), so order_id is UNIQUE rather than a plain foreign key.
--
-- ACCOUNT_TRANSACTIONS is an append-only ledger of cash movements. It does
-- not replace accounts.cash_balance (the live, authoritative balance) --
-- it is the audit trail of what moved it and why.
--
-- orders.executed_price and orders.filled_quantity (added in 006) are left
-- untouched here even though TRADES now duplicates the fill-price fact,
-- because trade-api-service/src/main/resources/mapper/OrderMapper.xml reads
-- and writes executed_price directly. Retiring those two columns from ORDERS
-- needs a coordinated change to that mapper (and the Java code around it)
-- and is intentionally left for a follow-up migration, not bundled here.
--
-- This migration is written to be safely re-runnable: every CREATE and every
-- ADD COLUMN is guarded, so running apply.sh again on a database that already
-- has this migration applied is a no-op here, not an error.

CREATE TABLE IF NOT EXISTS trades (
    id                UUID          NOT NULL DEFAULT gen_random_uuid(),
    order_id          UUID          NOT NULL,
    quantity          INT           NOT NULL,
    execution_price   NUMERIC(18,2) NOT NULL,
    broker_reference  VARCHAR(100),
    executed_at       TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_trades PRIMARY KEY (id),
    CONSTRAINT uq_trades_order_id UNIQUE (order_id),
    CONSTRAINT fk_trades_order
        FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_trades_quantity_positive
        CHECK (quantity > 0),
    CONSTRAINT ck_trades_execution_price_positive
        CHECK (execution_price > 0)
);

CREATE INDEX IF NOT EXISTS ix_trades_order_id ON trades (order_id);

CREATE TABLE IF NOT EXISTS account_transactions (
    id                BIGINT        GENERATED ALWAYS AS IDENTITY,
    account_id        BIGINT        NOT NULL,
    transaction_type  VARCHAR(30)   NOT NULL,
    amount            NUMERIC(18,2) NOT NULL,
    reference_type    VARCHAR(30),
    reference_id      VARCHAR(100),
    created_at        TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_account_transactions PRIMARY KEY (id),
    CONSTRAINT fk_account_transactions_account
        FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_account_transactions_type
        CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'BUY', 'SELL')),
    CONSTRAINT ck_account_transactions_amount_nonzero
        CHECK (amount <> 0)
);

CREATE INDEX IF NOT EXISTS ix_account_transactions_account_created
    ON account_transactions (account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_account_transactions_reference
    ON account_transactions (reference_type, reference_id);

-- Rejection reason: currently the Trade Executor only publishes the reject
-- reason onto Kafka (trade-events), never persists it, so "why was this
-- order rejected" is unanswerable from the database. NULL unless
-- status = 'REJECTED'.
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(255);

COMMENT ON TABLE trades IS
    'What was actually executed for an order. At most one row per order -- partial fills are out of scope.';
COMMENT ON TABLE account_transactions IS
    'Append-only ledger of cash movements. accounts.cash_balance is the live balance; this table is the audit trail of what moved it.';
COMMENT ON COLUMN orders.rejection_reason IS
    'Why the executor rejected the order. NULL unless status = REJECTED.';