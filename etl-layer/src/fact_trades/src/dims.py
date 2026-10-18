"""
Loads the three dimensions, in the order the contract requires:
dim_date, then dim_instrument, then dim_account, then (elsewhere) fact_trades.

Scope note: dim_account has SCD-Type-2 shaped columns (effective_date,
end_date, is_current) so the schema can track an account's history over
time. This sprint's scope is explicitly "one incremental load... no history
rebuild" -- so this pipeline inserts an account as a new current row the
first time it's seen, and does not attempt to detect or close out changed
attributes on existing accounts. Full SCD2 change-tracking is a real gap,
deliberately deferred, not an oversight.
"""

import datetime as dt
import duckdb


_DAY_NAMES = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
_MONTH_NAMES = ["January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December"]


def load_dim_dates(duck_con: duckdb.DuckDBPyConnection, dates: set[dt.date]) -> None:
    """Adds any date in `dates` that isn't already in dim_date. Never touches
    an existing row -- a date's calendar facts never change."""

    for full_date in sorted(dates):
        date_key = int(full_date.strftime("%Y%m%d"))
        exists = duck_con.execute(
            "SELECT 1 FROM dim_date WHERE date_key = ?", [date_key]
        ).fetchone()
        if exists:
            continue

        duck_con.execute("""
            INSERT INTO dim_date (
                date_key, full_date, day, month, year, quarter,
                day_of_week, day_name, month_name, is_weekday
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, [
            date_key,
            full_date,
            full_date.day,
            full_date.month,
            full_date.year,
            (full_date.month - 1) // 3 + 1,
            full_date.weekday(),  # 0=Monday .. 6=Sunday
            _DAY_NAMES[full_date.weekday()],
            _MONTH_NAMES[full_date.month - 1],
            full_date.weekday() < 5,
        ])


def load_dim_instruments(pg_conn, duck_con: duckdb.DuckDBPyConnection) -> None:
    """Upserts every instrument Postgres knows about. Small, static-ish
    reference table -- loading all of it every run is cheap and simple,
    unlike accounts/orders which are genuinely incremental."""

    with pg_conn.cursor() as cur:
        cur.execute("""
            SELECT symbol, instrument_name, asset_class, currency, tradable
            FROM instruments
        """)
        rows = cur.fetchall()

    next_key_row = duck_con.execute(
        "SELECT COALESCE(MAX(instrument_key), 0) FROM dim_instrument"
    ).fetchone()
    next_key = next_key_row[0] + 1

    for symbol, name, asset_class, currency, tradable in rows:
        existing = duck_con.execute(
            "SELECT instrument_key FROM dim_instrument WHERE symbol = ?", [symbol]
        ).fetchone()

        exchange = _infer_exchange(symbol)

        if existing:
            duck_con.execute("""
                UPDATE dim_instrument
                   SET name = ?, asset_class = ?, currency = ?,
                       exchange = ?, tradable = ?, loaded_at = ?
                 WHERE symbol = ?
            """, [name, asset_class, currency, exchange, tradable,
                  dt.datetime.now(dt.timezone.utc).replace(tzinfo=None), symbol])
        else:
            duck_con.execute("""
                INSERT INTO dim_instrument (
                    instrument_key, symbol, name, asset_class, currency,
                    exchange, tradable, loaded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, [next_key, symbol, name, asset_class, currency,
                  exchange, tradable, dt.datetime.now(dt.timezone.utc).replace(tzinfo=None)])
            next_key += 1


def _infer_exchange(symbol: str) -> str | None:
    """instruments.symbol carries the exchange as a suffix/prefix convention
    (per contracts/api-schemas/trade-api.yaml): .NS -> NSE, .BO -> BSE, FX: -> FX,
    X: -> CRYPTO. The source table has no separate exchange column, so this
    derives it rather than leaving a resolvable fact as NULL. Anything that
    doesn't match a known convention (e.g. a bare US ticker) stays NULL --
    a guess would be worse than an honest gap here."""

    if symbol.endswith(".NS"):
        return "NSE"
    if symbol.endswith(".BO"):
        return "BSE"
    if symbol.startswith("FX:"):
        return "FX"
    if symbol.startswith("X:"):
        return "CRYPTO"
    return None


def load_dim_accounts(pg_conn, duck_con: duckdb.DuckDBPyConnection) -> None:
    """Inserts any account not yet present as dim_account's current row.
    See the module docstring for why this is insert-only, not full SCD2."""

    with pg_conn.cursor() as cur:
        cur.execute("""
            SELECT id, account_id, holder_name, status
            FROM accounts
        """)
        rows = cur.fetchall()

    next_key_row = duck_con.execute(
        "SELECT COALESCE(MAX(account_key), 0) FROM dim_account"
    ).fetchone()
    next_key = next_key_row[0] + 1

    today = dt.date.today()

    for source_id, account_id, holder_name, status in rows:
        existing = duck_con.execute(
            "SELECT account_key FROM dim_account WHERE source_id = ? AND is_current = TRUE",
            [source_id],
        ).fetchone()
        if existing:
            continue

        duck_con.execute("""
            INSERT INTO dim_account (
                account_key, account_id, holder_name, status,
                effective_date, end_date, is_current, source_id, loaded_at
            ) VALUES (?, ?, ?, ?, ?, NULL, TRUE, ?, ?)
        """, [next_key, account_id, holder_name, status,
              today, source_id, dt.datetime.now(dt.timezone.utc).replace(tzinfo=None)])
        next_key += 1
