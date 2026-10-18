"""
Stage 2 of 3: TRANSFORM.

Reads whatever's sitting in stg_orders_extracted that hasn't already been
resolved *in its own batch* (an order that changed since an earlier batch is
extracted again under a new batch_id and must be checked again -- otherwise a
NEW order would stay NEW in FACT_TRADES after the executor filled it), runs
every check the contract's load and data quality section names, and writes
each row to exactly one of two places -- never both, never neither, and
never silently dropped.

trade_value is always recomputed here, never trusted from anywhere upstream:
quantity * executed_price when the order actually filled, quantity * the
requested limit price otherwise (a NEW/REJECTED/CANCELLED order has no
executed_price, but still needs a non-null trade_value per the contract --
using the requested price for the unfulfilled notional is the modelling
choice made here, and it's what makes trade_value meaningful for every row,
not just fills).
"""

import datetime as dt

import duckdb

_VALID_SIDES = {"BUY", "SELL"}
_VALID_STATUSES = {"NEW", "FILLED", "REJECTED", "CANCELLED"}


def run_transform(duck_con: duckdb.DuckDBPyConnection, batch_id: str | None = None) -> tuple[int, int]:
    """Returns (valid_count, dead_lettered_count)."""

    if batch_id:
        rows = duck_con.execute("""
            SELECT e.id, e.account_id, e.symbol, e.side, e.quantity, e.price,
                   e.executed_price, e.status, e.created_on, e.batch_id
            FROM stg_orders_extracted e
            WHERE e.batch_id = ?
              AND NOT EXISTS (SELECT 1 FROM stg_orders_valid v WHERE v.id = e.id AND v.batch_id = e.batch_id)
              AND NOT EXISTS (SELECT 1 FROM dead_letter_trades d WHERE d.source_order_id = e.id AND d.batch_id = e.batch_id)
        """, [batch_id]).fetchall()
    else:
        rows = duck_con.execute("""
            SELECT e.id, e.account_id, e.symbol, e.side, e.quantity, e.price,
                   e.executed_price, e.status, e.created_on, e.batch_id
            FROM stg_orders_extracted e
            WHERE NOT EXISTS (SELECT 1 FROM stg_orders_valid v WHERE v.id = e.id AND v.batch_id = e.batch_id)
              AND NOT EXISTS (SELECT 1 FROM dead_letter_trades d WHERE d.source_order_id = e.id AND d.batch_id = e.batch_id)
        """).fetchall()

    known_symbols = {
        r[0] for r in duck_con.execute("SELECT symbol FROM dim_instrument").fetchall()
    }
    known_account_source_ids = {
        r[0] for r in duck_con.execute(
            "SELECT source_id FROM dim_account WHERE is_current = TRUE"
        ).fetchall()
    }

    next_dl_id_row = duck_con.execute(
        "SELECT COALESCE(MAX(id), 0) FROM dead_letter_trades"
    ).fetchone()
    next_dl_id = next_dl_id_row[0] + 1

    valid_count = 0
    dead_count = 0

    for (order_id, account_id, symbol, side, quantity, price,
         executed_price, status, created_on, row_batch_id) in rows:

        reasons: list[str] = []

        if account_id not in known_account_source_ids:
            reasons.append("unknown account: dim_account load likely skipped or account missing in source")
        if symbol not in known_symbols:
            reasons.append("unknown instrument: dim_instrument load likely skipped or symbol missing in source")
        if quantity is None or quantity <= 0:
            reasons.append("non-positive or missing quantity")
        if price is None or price <= 0:
            reasons.append("non-positive or missing price")
        if side not in _VALID_SIDES:
            reasons.append(f"invalid side: {side!r}")
        if status not in _VALID_STATUSES:
            reasons.append(f"invalid status: {status!r}")
        if status == "FILLED" and (executed_price is None or executed_price <= 0):
            reasons.append("status is FILLED but executed_price is missing or non-positive")

        if reasons:
            duck_con.execute("""
                INSERT INTO dead_letter_trades (
                    id, source_order_id, batch_id, reason,
                    raw_account_id, raw_symbol, raw_side, raw_quantity,
                    raw_price, raw_executed_price, raw_status, raw_created_on,
                    dead_lettered_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, [next_dl_id, order_id, row_batch_id, "; ".join(reasons),
                  account_id, symbol, side, quantity, price, executed_price,
                  status, created_on, dt.datetime.now(dt.timezone.utc).replace(tzinfo=None)])
            next_dl_id += 1
            dead_count += 1
            continue

        trade_value = quantity * (executed_price if status == "FILLED" else price)

        duck_con.execute("""
            INSERT INTO stg_orders_valid (
                id, account_id, symbol, side, quantity, price,
                executed_price, status, trade_value, created_on, batch_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                account_id = excluded.account_id,
                symbol = excluded.symbol,
                side = excluded.side,
                quantity = excluded.quantity,
                price = excluded.price,
                executed_price = excluded.executed_price,
                status = excluded.status,
                trade_value = excluded.trade_value,
                created_on = excluded.created_on,
                batch_id = excluded.batch_id
        """, [order_id, account_id, symbol, side, quantity, price,
              executed_price, status, trade_value, created_on, row_batch_id])
        valid_count += 1

    return valid_count, dead_count
