import pandas as pd

from trading_analytics.transform import transform


def test_transform_basic():
    response = {
        "data": {
            "symbol": "AAPL",
            "candles": [
                {"date": "2025-01-02", "open": 11, "high": 13, "low": 10, "close": 12, "volume": 20},
                {"date": "2025-01-01", "open": 9, "high": 10, "low": 8, "close": 9, "volume": 10},
            ],
        }
    }

    frame = transform(response)

    assert len(frame) == 2
    assert list(frame.columns)[:3] == ["symbol", "date", "open"]
    # dates are parsed
    assert pd.api.types.is_datetime64_any_dtype(frame["date"]) or pd.api.types.is_datetime64tz_dtype(frame["date"])


def test_transform_skips_malformed():
    response = [
        {"date": "2025-01-01", "open": 9, "high": 10, "low": 8, "close": 9},
        "not-an-object",
        {"date": "bad-date", "open": "x", "high": 1, "low": 0, "close": 1},
    ]

    frame = transform(response)

    assert len(frame) == 1
