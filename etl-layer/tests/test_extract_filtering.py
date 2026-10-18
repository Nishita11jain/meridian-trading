"""Test date range filtering functionality."""

from trading_analytics.extract import filter_candles_by_range


def test_filter_candles_1y():
    """Test filtering candles to last 1 year."""
    response = {
        "symbol": "AAPL",
        "data": {
            "candles": [
                {"date": "2020-01-01", "close": 100},
                {"date": "2021-01-01", "close": 110},
                {"date": "2022-01-01", "close": 120},
                {"date": "2023-01-01", "close": 130},
                {"date": "2024-01-01", "close": 140},
                {"date": "2025-01-01", "close": 150},
                {"date": "2026-01-01", "close": 160},
            ]
        }
    }
    
    filtered = filter_candles_by_range(response, "1y")
    
    # Should only have candles from last ~1 year
    # (from 2025-01-01 onwards, since max date is 2026-01-01)
    candles = filtered["data"]["candles"]
    assert len(candles) <= len(response["data"]["candles"])
    assert all(c["date"] >= "2025-01-01" for c in candles), \
        "All candles should be from last year"


def test_filter_candles_6y():
    """Test filtering candles to last 6 years."""
    response = {
        "symbol": "AAPL",
        "data": {
            "candles": [
                {"date": "2020-01-01", "close": 100},
                {"date": "2021-01-01", "close": 110},
                {"date": "2022-01-01", "close": 120},
                {"date": "2023-01-01", "close": 130},
                {"date": "2024-01-01", "close": 140},
                {"date": "2025-01-01", "close": 150},
                {"date": "2026-01-01", "close": 160},
            ]
        }
    }
    
    filtered = filter_candles_by_range(response, "6y")
    
    # Should have more candles than 1y filter
    candles_6y = filtered["data"]["candles"]
    
    # Filter to 1y for comparison
    filtered_1y = filter_candles_by_range(response, "1y")
    candles_1y = filtered_1y["data"]["candles"]
    
    assert len(candles_6y) >= len(candles_1y), "6y should have >= candles than 1y"


def test_filter_candles_max():
    """Test filtering with 'max' returns all candles."""
    response = {
        "symbol": "AAPL",
        "data": {
            "candles": [
                {"date": "2020-01-01", "close": 100},
                {"date": "2021-01-01", "close": 110},
                {"date": "2022-01-01", "close": 120},
            ]
        }
    }
    
    filtered = filter_candles_by_range(response, "max")
    
    # Should have all candles
    assert len(filtered["data"]["candles"]) == len(response["data"]["candles"])


def test_filter_candles_empty_list():
    """Test filtering empty candles list returns response unchanged."""
    response = {
        "symbol": "AAPL",
        "data": {
            "candles": []
        }
    }
    
    filtered = filter_candles_by_range(response, "1y")
    
    # Should be unchanged
    assert filtered == response


def test_filter_candles_list_format():
    """Test filtering works with list format (not dict)."""
    candles = [
        {"date": "2025-01-01", "close": 150},
        {"date": "2026-01-01", "close": 160},
    ]
    
    filtered = filter_candles_by_range(candles, "1y")
    
    # Should be a filtered list
    assert isinstance(filtered, list)
    assert len(filtered) > 0


def test_filter_candles_invalid_range_defaults_to_all():
    """Test that invalid range format defaults to returning all data."""
    response = {
        "symbol": "AAPL",
        "data": {
            "candles": [
                {"date": "2020-01-01", "close": 100},
                {"date": "2026-01-01", "close": 160},
            ]
        }
    }
    
    # Invalid range format
    filtered = filter_candles_by_range(response, "invalid")
    
    # Should return all data
    assert len(filtered["data"]["candles"]) == len(response["data"]["candles"])


def test_filter_preserves_response_structure():
    """Test that filtering preserves the response structure."""
    response = {
        "symbol": "AAPL",
        "data": {
            "interval": "1d",
            "currency": "USD",
            "candles": [
                {"date": "2025-01-01", "close": 150},
                {"date": "2026-01-01", "close": 160},
            ]
        }
    }
    
    filtered = filter_candles_by_range(response, "1y")
    
    # Should preserve structure
    assert "symbol" in filtered
    assert "data" in filtered
    assert "interval" in filtered["data"]
    assert "currency" in filtered["data"]
    assert "candles" in filtered["data"]
