import datetime as dt

import duckdb
import pytest

from fact_trades.src.schema import ensure_schema
from fact_trades.src.extract import run_extract
from fact_trades.src.transform import run_transform
from fact_trades.src.load import run_load


class _FakeCursor:
    def __init__(self, rows):
        self._rows = rows
        self._last_result = []

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql, params=None):
        sql_upper = sql.strip().upper()
        if sql_upper.startswith("SELECT ID, ACCOUNT_ID, SYMBOL, SIDE"):
            self._last_result = self._rows["orders"]
        elif sql_upper.startswith("SELECT SYMBOL, INSTRUMENT_NAME"):
            self._last_result = self._rows["instruments"]
        elif sql_upper.startswith("SELECT ID, ACCOUNT_ID, HOLDER_NAME, STATUS"):
            self._last_result = self._rows["accounts"]
        else:
            self._last_result = []

    def fetchall(self):
        return self._last_result


class _FakePgConn:
    def __init__(self, orders, instruments, accounts):
        self._rows = {"orders": orders, "instruments": instruments, "accounts": accounts}

    def cursor(self):
        return _FakeCursor(self._rows)

    def close(self):
        pass


ACCOUNTS = [(1, "ACC-0001", "Alice Holder", "ACTIVE")]
INSTRUMENTS = [("TCS.NS", "Tata Consultancy Services", "EQUITY", "INR", True)]


@pytest.fixture
def duck_con():
    con = duckdb.connect(":memory:")
    ensure_schema(con)
    yield con
    con.close()


def test_incremental_load_populates_fact_trades(duck_con):
    orders = [
        ("11111111-1111-1111-1111-111111111111", 1, "TCS.NS", "BUY", 10,
         2300.00, 2310.50, "FILLED", dt.datetime(2026, 9, 17, 10, 0, 0)),
    ]
    pg_conn = _FakePgConn(orders, INSTRUMENTS, ACCOUNTS)

    batch_id = run_extract(pg_conn, duck_con)
    assert batch_id != ""

    valid_count, dead_count = run_transform(duck_con, batch_id=batch_id)
    assert valid_count == 1
    assert dead_count == 0

    written = run_load(duck_con, batch_id=batch_id)
    assert written == 1

    row = duck_con.execute(
        "SELECT status, trade_value, source_order_id FROM fact_trades"
    ).fetchone()
    assert row[0] == "FILLED"
    assert row[1] == 23105.0  # 10 * 2310.50
    assert row[2] == "11111111-1111-1111-1111-111111111111"


def test_second_load_with_no_new_data_adds_no_rows(duck_con):
    orders = [
        ("22222222-2222-2222-2222-222222222222", 1, "TCS.NS", "BUY", 5,
         2300.00, 2305.00, "FILLED", dt.datetime(2026, 9, 17, 11, 0, 0)),
    ]
    pg_conn = _FakePgConn(orders, INSTRUMENTS, ACCOUNTS)

    batch_id = run_extract(pg_conn, duck_con)
    run_transform(duck_con, batch_id=batch_id)
    run_load(duck_con, batch_id=batch_id)

    count_after_first = duck_con.execute("SELECT COUNT(*) FROM fact_trades").fetchone()[0]

    # Second run: watermark has advanced past this order, so Postgres
    # (simulated here) returns the same rows again -- extract must find
    # nothing new because it filters by created_on > watermark. Since our
    # fake cursor doesn't filter by date itself, simulate "nothing new" the
    # way the watermark actually enforces it: same orders list, but the
    # watermark now equals this order's created_on, so a real Postgres
    # WHERE created_on > watermark would exclude it. We assert on the
    # watermark instead, which is what actually prevents re-extraction.
    from fact_trades.src.extract import get_watermark
    watermark = get_watermark(duck_con)
    assert watermark == dt.datetime(2026, 9, 17, 11, 0, 0)

    # Re-running load/transform again on the same already-loaded batch_id
    # must not add a second fact row for the same source_order_id.
    run_transform(duck_con, batch_id=batch_id)
    run_load(duck_con, batch_id=batch_id)
    count_after_second = duck_con.execute("SELECT COUNT(*) FROM fact_trades").fetchone()[0]

    assert count_after_second == count_after_first == 1


def test_transform_handles_nulls_and_type_mismatches(duck_con):
    orders = [
        ("33333333-3333-3333-3333-333333333333", 1, "TCS.NS", "BUY", None,
         2300.00, None, "NEW", dt.datetime(2026, 9, 17, 12, 0, 0)),
    ]
    pg_conn = _FakePgConn(orders, INSTRUMENTS, ACCOUNTS)

    batch_id = run_extract(pg_conn, duck_con)
    valid_count, dead_count = run_transform(duck_con, batch_id=batch_id)

    assert valid_count == 0
    assert dead_count == 1

    reason = duck_con.execute(
        "SELECT reason FROM dead_letter_trades WHERE source_order_id = ?",
        ["33333333-3333-3333-3333-333333333333"],
    ).fetchone()[0]
    assert "quantity" in reason


def test_invalid_row_is_dead_lettered_and_load_continues(duck_con):
    orders = [
        ("44444444-4444-4444-4444-444444444444", 1, "TCS.NS", "BUY", 10,
         2300.00, 2310.00, "FILLED", dt.datetime(2026, 9, 17, 13, 0, 0)),
        # unknown symbol -> should be dead-lettered, not crash the batch
        ("55555555-5555-5555-5555-555555555555", 1, "GHOST.NS", "BUY", 5,
         100.00, None, "REJECTED", dt.datetime(2026, 9, 17, 13, 5, 0)),
    ]
    pg_conn = _FakePgConn(orders, INSTRUMENTS, ACCOUNTS)

    batch_id = run_extract(pg_conn, duck_con)
    valid_count, dead_count = run_transform(duck_con, batch_id=batch_id)

    assert valid_count == 1
    assert dead_count == 1

    written = run_load(duck_con, batch_id=batch_id)
    assert written == 1  # the good row still loads despite the bad one

    dl_reason = duck_con.execute(
        "SELECT reason, batch_id FROM dead_letter_trades WHERE source_order_id = ?",
        ["55555555-5555-5555-5555-555555555555"],
    ).fetchone()
    assert "unknown instrument" in dl_reason[0]
    assert dl_reason[1] == batch_id


def test_order_extracted_as_new_becomes_filled_after_the_executor_updates_it(duck_con):
    """Fills are asynchronous: the order is first loaded as NEW, then the
    executor fills it and sets updated_on. The next incremental load must pick
    the change up (watermark on updated_on) and the fact row must end FILLED."""
    from fact_trades.src.extract import get_watermark

    order_id = "66666666-6666-6666-6666-666666666666"
    created = dt.datetime(2026, 9, 17, 14, 0, 0)
    filled_at = dt.datetime(2026, 9, 17, 14, 0, 5)

    # First load: the order is still NEW; there is no updated_on yet.
    new_row = (order_id, 1, "TCS.NS", "BUY", 10, 2300.00, None, "NEW", created, created)
    batch_1 = run_extract(_FakePgConn([new_row], INSTRUMENTS, ACCOUNTS), duck_con)
    run_transform(duck_con, batch_id=batch_1)
    run_load(duck_con, batch_id=batch_1)
    assert duck_con.execute(
        "SELECT status FROM fact_trades WHERE source_order_id = ?", [order_id]
    ).fetchone()[0] == "NEW"

    # Second load: same order, now FILLED, changed_on later than created_on.
    filled_row = (order_id, 1, "TCS.NS", "BUY", 10, 2300.00, 2298.50, "FILLED", created, filled_at)
    batch_2 = run_extract(_FakePgConn([filled_row], INSTRUMENTS, ACCOUNTS), duck_con)
    valid_count, dead_count = run_transform(duck_con, batch_id=batch_2)
    run_load(duck_con, batch_id=batch_2)

    assert (valid_count, dead_count) == (1, 0)
    rows = duck_con.execute(
        "SELECT status, executed_price, trade_value FROM fact_trades WHERE source_order_id = ?",
        [order_id],
    ).fetchall()
    assert rows == [("FILLED", 2298.50, 22985.0)]
    assert get_watermark(duck_con) == filled_at


def test_rerunning_transform_on_a_batch_does_not_duplicate_dead_letters(duck_con):
    orders = [
        ("77777777-7777-7777-7777-777777777777", 1, "GHOST.NS", "BUY", 5,
         100.00, None, "REJECTED", dt.datetime(2026, 9, 17, 15, 0, 0)),
    ]
    batch_id = run_extract(_FakePgConn(orders, INSTRUMENTS, ACCOUNTS), duck_con)

    run_transform(duck_con, batch_id=batch_id)
    run_transform(duck_con, batch_id=batch_id)

    assert duck_con.execute("SELECT COUNT(*) FROM dead_letter_trades").fetchone()[0] == 1
