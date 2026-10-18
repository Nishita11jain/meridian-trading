import json

from trading_analytics.cache import load_cached_response, save_raw_response


def test_raw_response_round_trip(tmp_path):
    """Test cache save/load with new symbol-based (not range-based) cache key."""
    response = {"symbol": "AAPL", "candles": [{"close": 123.45}]}

    # Save with range_name (kept for backward compatibility)
    save_raw_response(tmp_path, "aapl", "1y", response)

    # Load should work (range_name ignored in cache key lookup)
    assert load_cached_response(tmp_path, "AAPL", "1y") == response
    
    # Verify file created with new symbol-based name (no range)
    assert json.loads((tmp_path / "AAPL.json").read_text()) == response
    
    # Old range-specific file should NOT exist
    assert not (tmp_path / "AAPL_1y.json").exists()


def test_cache_load_ignores_range_name(tmp_path):
    """Test that different range_names use the same cache file."""
    response = {"symbol": "AAPL", "candles": [{"close": 123.45}]}

    # Save with 1y range
    save_raw_response(tmp_path, "AAPL", "1y", response)
    
    # Load with different range (6y) should still find cache
    assert load_cached_response(tmp_path, "AAPL", "6y") == response
    assert load_cached_response(tmp_path, "AAPL", "max") == response


def test_missing_cache_returns_none(tmp_path):
    assert load_cached_response(tmp_path, "AAPL", "1y") is None