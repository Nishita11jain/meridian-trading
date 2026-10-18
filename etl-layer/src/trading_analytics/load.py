from pathlib import Path

import duckdb
import pandas as pd


def load(frame: pd.DataFrame, database_path: Path) -> None:
    """Load the transformed candle data into the DuckDB analytics database."""

    database_path.parent.mkdir(parents=True, exist_ok=True)

    with duckdb.connect(str(database_path)) as connection:
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS candles (
                symbol VARCHAR,
                date TIMESTAMP,
                open DOUBLE,
                high DOUBLE,
                low DOUBLE,
                close DOUBLE,
                volume DOUBLE,
                volume_flagged BOOLEAN
            )
            """
        )

        if frame.empty:
            return

        symbols = frame["symbol"].unique().tolist()
        placeholders = ", ".join("?" for _ in symbols)
        connection.execute(
            f"DELETE FROM candles WHERE symbol IN ({placeholders})",
            symbols,
        )
        connection.execute("INSERT INTO candles SELECT * FROM frame")