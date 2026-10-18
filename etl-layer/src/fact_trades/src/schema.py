"""
Creates every table this pipeline needs in the DuckDB file, idempotently.

The four star-schema tables (dim_account, dim_instrument, dim_date,
fact_trades) come verbatim from contracts/analytics-schemas/analytics-schema.sql -- this
module does not redefine that contract, it just makes sure the tables exist
before anything tries to write to them.

Two additional tables exist purely for this pipeline's own bookkeeping and
are NOT part of the analytics contract:

- load_watermark: one row per pipeline, remembering the newest
  COALESCE(orders.updated_on, orders.created_on) this pipeline has successfully loaded, so a re-run only
  reads what's new.
- dead_letter_trades: every order row that failed a quality check, with the
  reason it failed and which batch it came from. Never dropped silently.
"""

import duckdb


def ensure_schema(duck_con: duckdb.DuckDBPyConnection) -> None:
    duck_con.execute("""
        CREATE TABLE IF NOT EXISTS dim_account (
            account_key BIGINT NOT NULL,
            account_id VARCHAR(32) NOT NULL,
            holder_name VARCHAR(255) NOT NULL,
            status VARCHAR(20) NOT NULL,
            effective_date DATE NOT NULL,
            end_date DATE,
            is_current BOOLEAN NOT NULL,
            source_id BIGINT NOT NULL,
            loaded_at TIMESTAMP NOT NULL,
            CONSTRAINT pk_dim_account PRIMARY KEY (account_key)
        )
    """)

    duck_con.execute("""
        CREATE TABLE IF NOT EXISTS dim_instrument (
            instrument_key BIGINT NOT NULL,
            symbol VARCHAR(20) NOT NULL,
            name VARCHAR(255) NOT NULL,
            asset_class VARCHAR(20) NOT NULL,
            currency CHAR(3) NOT NULL,
            exchange VARCHAR(20),
            tradable BOOLEAN NOT NULL,
            loaded_at TIMESTAMP NOT NULL,
            CONSTRAINT pk_dim_instrument PRIMARY KEY (instrument_key),
            CONSTRAINT uq_dim_instrument_symbol UNIQUE (symbol)
        )
    """)

    duck_con.execute("""
        CREATE TABLE IF NOT EXISTS dim_date (
            date_key INTEGER NOT NULL,
            full_date DATE NOT NULL,
            day INTEGER NOT NULL,
            month INTEGER NOT NULL,
            year INTEGER NOT NULL,
            quarter INTEGER NOT NULL,
            day_of_week INTEGER NOT NULL,
            day_name VARCHAR(9) NOT NULL,
            month_name VARCHAR(9) NOT NULL,
            is_weekday BOOLEAN NOT NULL,
            CONSTRAINT pk_dim_date PRIMARY KEY (date_key),
            CONSTRAINT uq_dim_date_full_date UNIQUE (full_date)
        )
    """)

    duck_con.execute("""
        CREATE TABLE IF NOT EXISTS fact_trades (
            trade_key BIGINT NOT NULL,
            account_key BIGINT NOT NULL,
            instrument_key BIGINT NOT NULL,
            date_key INTEGER NOT NULL,
            side VARCHAR(4) NOT NULL,
            quantity INTEGER NOT NULL,
            price DECIMAL(18,2) NOT NULL,
            status VARCHAR(20) NOT NULL,
            executed_price DECIMAL(18,2),
            trade_value DECIMAL(18,2) NOT NULL,
            source_order_id VARCHAR(36) NOT NULL,
            created_at TIMESTAMP NOT NULL,
            loaded_at TIMESTAMP NOT NULL,
            CONSTRAINT pk_fact_trades PRIMARY KEY (trade_key),
            CONSTRAINT uq_fact_trades_source UNIQUE (source_order_id),
            CONSTRAINT fk_fact_trades_account FOREIGN KEY (account_key) REFERENCES dim_account (account_key),
            CONSTRAINT fk_fact_trades_instrument FOREIGN KEY (instrument_key) REFERENCES dim_instrument (instrument_key),
            CONSTRAINT fk_fact_trades_date FOREIGN KEY (date_key) REFERENCES dim_date (date_key)
        )
    """)

    # --- pipeline bookkeeping, not part of the analytics contract ---

    duck_con.execute("""
        CREATE TABLE IF NOT EXISTS load_watermark (
            pipeline_name VARCHAR NOT NULL,
            last_loaded_ts TIMESTAMP NOT NULL,
            CONSTRAINT pk_load_watermark PRIMARY KEY (pipeline_name)
        )
    """)

    duck_con.execute("""
        CREATE TABLE IF NOT EXISTS dead_letter_trades (
            id BIGINT NOT NULL,
            source_order_id VARCHAR(36),
            batch_id VARCHAR(36) NOT NULL,
            reason VARCHAR NOT NULL,
            raw_account_id BIGINT,
            raw_symbol VARCHAR(20),
            raw_side VARCHAR(10),
            raw_quantity INTEGER,
            raw_price DECIMAL(18,2),
            raw_executed_price DECIMAL(18,2),
            raw_status VARCHAR(20),
            raw_created_on TIMESTAMP,
            dead_lettered_at TIMESTAMP NOT NULL,
            CONSTRAINT pk_dead_letter_trades PRIMARY KEY (id)
        )
    """)

    # Staging tables: hold each stage's output so extract / transform / load
    # can be run as three genuinely separate commands, not just three
    # in-process function calls that only work back-to-back.

    duck_con.execute("""
        CREATE TABLE IF NOT EXISTS stg_orders_extracted (
            id VARCHAR(36) NOT NULL,
            account_id BIGINT NOT NULL,
            symbol VARCHAR(20) NOT NULL,
            side VARCHAR(10) NOT NULL,
            quantity INTEGER,
            price DECIMAL(18,2),
            executed_price DECIMAL(18,2),
            status VARCHAR(20) NOT NULL,
            created_on TIMESTAMP NOT NULL,
            batch_id VARCHAR(36) NOT NULL,
            CONSTRAINT pk_stg_orders_extracted PRIMARY KEY (id)
        )
    """)

    # When the order last changed (updated_on, else created_on): what the
    # watermark advances on. Added separately so a DuckDB file created before
    # this column existed is upgraded in place.
    duck_con.execute(
        "ALTER TABLE stg_orders_extracted ADD COLUMN IF NOT EXISTS changed_on TIMESTAMP"
    )

    duck_con.execute("""
        CREATE TABLE IF NOT EXISTS stg_orders_valid (
            id VARCHAR(36) NOT NULL,
            account_id BIGINT NOT NULL,
            symbol VARCHAR(20) NOT NULL,
            side VARCHAR(10) NOT NULL,
            quantity INTEGER NOT NULL,
            price DECIMAL(18,2) NOT NULL,
            executed_price DECIMAL(18,2),
            status VARCHAR(20) NOT NULL,
            trade_value DECIMAL(18,2) NOT NULL,
            created_on TIMESTAMP NOT NULL,
            batch_id VARCHAR(36) NOT NULL,
            CONSTRAINT pk_stg_orders_valid PRIMARY KEY (id)
        )
    """)
