from trading_analytics import extract


def test_extract_caching(tmp_path, monkeypatch):
    """Test that extract_candles caches and reuses data."""
    calls = []
    response = {"symbol": "AAPL", "candles": []}

    monkeypatch.setattr(extract, "get_api_key", lambda: "test-key")
    monkeypatch.setattr(extract, "get_candles", lambda *a, **k: calls.append(1) or response)

    result1 = extract.extract_candles("https://api.test", "AAPL", "1y", cache_dir=tmp_path)
    result2 = extract.extract_candles("https://api.test", "AAPL", "1y", cache_dir=tmp_path)

    assert result1 == response
    assert result2 == response
    assert calls == [1], "Should only call API once, second call should use cache"


def test_extract_cross_range_reuse(tmp_path, monkeypatch):
    """
    Test cross-range cache reuse: 6y data should serve 1y requests.
    
    This is the fix for the original issue where 1y and 6y created separate API calls.
    """
    calls = []
    response = {
        "symbol": "AAPL",
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

    monkeypatch.setattr(extract, "get_api_key", lambda: "test-key")
    monkeypatch.setattr(extract, "get_candles", lambda *a, **k: calls.append(1) or response)

    # First: fetch 6 years of data
    result_6y = extract.extract_candles("https://api.test", "AAPL", "6y", cache_dir=tmp_path)
    assert len(result_6y["candles"]) > 0, "Should have filtered 6y of data"
    assert calls == [1], "First call should hit API"

    # Second: fetch 1 year of data - should reuse 6y cache, NO NEW API CALL
    result_1y = extract.extract_candles("https://api.test", "AAPL", "1y", cache_dir=tmp_path)
    assert len(result_1y["candles"]) > 0, "Should have filtered 1y of data"
    assert calls == [1], "Second call should reuse cache, not call API again"
    
    # Third: fetch max data - should reuse cache
    result_max = extract.extract_candles("https://api.test", "AAPL", "max", cache_dir=tmp_path)
    assert calls == [1], "Third call should also reuse cache"
    
    # Verify 1y is subset of 6y (fewer candles)
    assert len(result_1y["candles"]) <= len(result_6y["candles"]), \
        "1y should have <= candles than 6y"
