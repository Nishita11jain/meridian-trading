import re
from typing import Any

import pandas as pd


COLUMNS = [
    "symbol",
    "date",
    "open",
    "high",
    "low",
    "close",
    "volume",
    "volume_flagged",
]

ISO_DATE_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def transform(raw_response: Any) -> pd.DataFrame:
    """Transform the complete Fauxnance response into a DataFrame."""

    if isinstance(raw_response, dict):
        data = raw_response.get("data", raw_response)

        if not isinstance(data, dict):
            raise ValueError("invalid response data")

        candles = data.get("candles", [])
        symbol = data.get("symbol", "UNKNOWN")

    elif isinstance(raw_response, list):
        candles = raw_response
        symbol = "UNKNOWN"

    else:
        raise ValueError("invalid response format")

    if not isinstance(candles, list):
        raise ValueError("response must contain a candles list")

    rows = []
    seen_dates = set()
    duplicate_dates = set()

    for candle in candles:
        if not isinstance(candle, dict):
            continue

        raw_date = candle.get("date")

        # Defect: a non-ISO date (e.g. "09/07/2026") is rejected outright
        # rather than handed to pandas, which would silently reparse it
        # under different format assumptions and produce the wrong date.
        if not isinstance(raw_date, str) or not ISO_DATE_PATTERN.match(raw_date):
            continue

        try:
            row = {
                "symbol": symbol,
                "date": pd.to_datetime(
                    raw_date,
                    utc=True,
                ),
                "open": float(candle["open"]),
                "high": float(candle["high"]),
                "low": float(candle["low"]),
                "close": float(candle["close"]),
                "volume": (
                    float(candle["volume"])
                    if candle.get("volume") is not None
                    else None
                ),
            }

        except (KeyError, TypeError, ValueError):
            continue

        if row["high"] < row["low"]:
            continue

        # Defect: negative volume is flagged, not dropped -- the price
        # data in the row is still usable even if volume looks wrong.
        row["volume_flagged"] = row["volume"] is not None and row["volume"] < 0

        # Track duplicate dates so every occurrence can be dropped below --
        # a duplicate with conflicting values is worse than no value at all.
        if raw_date in seen_dates:
            duplicate_dates.add(raw_date)
        seen_dates.add(raw_date)

        row["_raw_date"] = raw_date
        rows.append(row)

    # Defect: a date appearing more than once (with possibly conflicting
    # values) has every occurrence dropped, not just the second one.
    rows = [r for r in rows if r["_raw_date"] not in duplicate_dates]
    for r in rows:
        del r["_raw_date"]

    return pd.DataFrame(
        rows,
        columns=COLUMNS,
    )