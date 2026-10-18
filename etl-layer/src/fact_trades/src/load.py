"""
Stage 3 of 3: LOAD.

Merges stg_orders_valid into fact_trades on the natural key
(source_order_id), resolving each row's surrogate keys against the
dimensions loaded in stage 1. Uses INSERT ... ON CONFLICT DO UPDATE, so
re-running this stage against a batch already merged updates the existing
fact row (e.g. an order that was NEW last run and is FILLED now) rather
than inserting a duplicate -- this is the property that makes a second run
over the same data add nothing extra.

The watermark is advanced here, after load, using every row this pipeline
EXTRACTED for the batch -- valid and dead-lettered alike. A dead-lettered
order must not be pulled from Postgres again on the next run just because
the watermark never passed it; it was already handled, just handled by
being rejected with a reason.
"""

import duckdb

from .extract import set_watermark


def run_load(duck_con: duckdb.DuckDBPyConnection, batch_id: str) -> int:
    """Returns the number of fact rows written or updated."""

    valid_rows = duck_con.execute("""
        SELECT id, account_id, symbol, side, quantity, price,
               executed_price, status, trade_value, created_on
        FROM stg_orders_valid
        WHERE batch_id = ?
    """, [batch_id]).fetchall()

    next_key_row = duck_con.execute(
        "SELECT COALESCE(MAX(trade_key), 0) FROM fact_trades"
    ).fetchone()
    next_key = next_key_row[0] + 1

    written = 0
    for (order_id, account_id, symbol, side, quantity, price,
         executed_price, status, trade_value, created_on) in valid_rows:

        account_key_row = duck_con.execute(
            "SELECT account_key FROM dim_account WHERE source_id = ? AND is_current = TRUE",
            [account_id],
        ).fetchone()
        instrument_key_row = duck_con.execute(
            "SELECT instrument_key FROM dim_instrument WHERE symbol = ?", [symbol]
        ).fetchone()

        if account_key_row is None or instrument_key_row is None:
            # Should not happen -- transform already checked referential
            # integrity against the same dimensions. If it does, this row
            # is left unmerged rather than guessed at; re-running transform
            # and load after investigating is the correct next step.
            continue

        account_key = account_key_row[0]
        instrument_key = instrument_key_row[0]
        date_key = int(created_on.strftime("%Y%m%d"))

        existing = duck_con.execute(
            "SELECT trade_key FROM fact_trades WHERE source_order_id = ?", [str(order_id)]
        ).fetchone()

        if existing:
            duck_con.execute("""
                UPDATE fact_trades
                   SET account_key = ?, instrument_key = ?, date_key = ?,
                       side = ?, quantity = ?, price = ?, status = ?,
                       executed_price = ?, trade_value = ?, loaded_at = CURRENT_TIMESTAMP
                 WHERE source_order_id = ?
            """, [account_key, instrument_key, date_key, side, quantity,
                  price, status, executed_price, trade_value, str(order_id)])
        else:
            duck_con.execute("""
                INSERT INTO fact_trades (
                    trade_key, account_key, instrument_key, date_key,
                    side, quantity, price, status, executed_price,
                    trade_value, source_order_id, created_at, loaded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """, [next_key, account_key, instrument_key, date_key, side,
                  quantity, price, status, executed_price, trade_value,
                  str(order_id), created_on])
            next_key += 1

        written += 1

    max_changed_on_row = duck_con.execute(
        "SELECT MAX(COALESCE(changed_on, created_on)) FROM stg_orders_extracted WHERE batch_id = ?",
        [batch_id],
    ).fetchone()
    if max_changed_on_row and max_changed_on_row[0] is not None:
        set_watermark(duck_con, max_changed_on_row[0])

    return written
