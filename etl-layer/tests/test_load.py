from pathlib import Path

import pandas as pd
import duckdb

from trading_analytics import load


def test_load_inserts_candles(tmp_path):
    database_path = tmp_path / "analytics.duckdb"

    df = pd.DataFrame(
        [
            {
                "symbol": "AAPL",
                "date": pd.Timestamp("2025-01-01", tz="UTC"),
                "open": 10.0,
                "high": 12.0,
                "low": 9.0,
                "close": 11.0,
                "volume": 100,
                "volume_flagged": False,
            }
        ]
    )

    load.load(df, database_path)

    with duckdb.connect(str(database_path), read_only=True) as connection:
        symbol, open_price, close_price = connection.execute(
            "SELECT symbol, open, close FROM candles"
        ).fetchone()

    assert symbol == "AAPL"
    assert open_price == 10.0
    assert close_price == 11.0
