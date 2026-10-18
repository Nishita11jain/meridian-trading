"""
Stage 1 of 3: EXTRACT.

Reads the watermark, pulls every order created OR changed since it -- in
EVERY status, never filtered to fills only, because fill rate needs the
denominator too -- and writes them into the stg_orders_extracted staging
table so this stage can be run and inspected on its own before transform
runs.

The watermark is on COALESCE(updated_on, created_on), not created_on alone.
Fills are asynchronous: an order is extracted as NEW, and the Trade Executor
moves it to FILLED or REJECTED (and sets updated_on) a moment later. Filtering
on created_on would leave that order NEW in FACT_TRADES for good.

This stage also loads the three dimensions, in the contract's required
order (dim_date, then dim_instrument, then dim_account), because a
dimension has to exist before transform can check referential integrity
against it, and transform is the next stage. dim_instrument and dim_account
are loaded in full from their source tables every run -- both are small,
close to static, and re-checking all of them is cheap; dim_date is loaded
only for the dates the new order batch actually touches.
"""

import datetime as dt
import uuid

import duckdb

from . import dims

_PIPELINE_NAME = "fact_trades_incremental"
_EPOCH = dt.datetime(1970, 1, 1)


def get_watermark(duck_con: duckdb.DuckDBPyConnection) -> dt.datetime:
    row = duck_con.execute(
        "SELECT last_loaded_ts FROM load_watermark WHERE pipeline_name = ?",
        [_PIPELINE_NAME],
    ).fetchone()
    return row[0] if row else _EPOCH


def set_watermark(duck_con: duckdb.DuckDBPyConnection, new_ts: dt.datetime) -> None:
    duck_con.execute("""
        INSERT INTO load_watermark (pipeline_name, last_loaded_ts)
        VALUES (?, ?)
        ON CONFLICT (pipeline_name) DO UPDATE SET last_loaded_ts = excluded.last_loaded_ts
    """, [_PIPELINE_NAME, new_ts])


def run_extract(pg_conn, duck_con: duckdb.DuckDBPyConnection) -> str:
    """Returns the batch_id for this extract, so transform/load can be told
    which batch to work on, or '' if there was nothing new to extract."""

    watermark = get_watermark(duck_con)

    with pg_conn.cursor() as cur:
        cur.execute("""
            SELECT id, account_id, symbol, side, quantity, price,
                   executed_price, status, created_on,
                   COALESCE(updated_on, created_on) AS changed_on
            FROM orders
            WHERE COALESCE(updated_on, created_on) > %s
            ORDER BY COALESCE(updated_on, created_on)
        """, [watermark])
        rows = cur.fetchall()

    if not rows:
        return ""

    batch_id = str(uuid.uuid4())
    touched_dates: set[dt.date] = set()

    for row in rows:
        (order_id, account_id, symbol, side, quantity, price,
         executed_price, status, created_on) = row[:9]
        # A row without a change timestamp has, by definition, only its creation time.
        changed_on = row[9] if len(row) > 9 and row[9] is not None else created_on
        duck_con.execute("""
            INSERT INTO stg_orders_extracted (
                id, account_id, symbol, side, quantity, price,
                executed_price, status, created_on, changed_on, batch_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                account_id = excluded.account_id,
                symbol = excluded.symbol,
                side = excluded.side,
                quantity = excluded.quantity,
                price = excluded.price,
                executed_price = excluded.executed_price,
                status = excluded.status,
                created_on = excluded.created_on,
                changed_on = excluded.changed_on,
                batch_id = excluded.batch_id
        """, [str(order_id), account_id, symbol, side, quantity, price,
              executed_price, status, created_on, changed_on, batch_id])
        touched_dates.add(created_on.date())

    # Dimensions before facts, in the contract's order. dim_date only needs
    # the dates this batch actually touches; instrument/account are small
    # enough to fully refresh every run.
    dims.load_dim_dates(duck_con, touched_dates)
    dims.load_dim_instruments(pg_conn, duck_con)
    dims.load_dim_accounts(pg_conn, duck_con)

    return batch_id
