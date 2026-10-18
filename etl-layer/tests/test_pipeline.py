from trading_analytics import extract
from trading_analytics.client import ClientHTTPError
from trading_analytics.pipeline import run_symbols


def test_extract_caches_response(tmp_path, monkeypatch):
    calls = []
    response = {
        "symbol": "AAPL",
        "candles": [{"close": 123.45}],
    }

    monkeypatch.setattr(
        extract,
        "get_api_key",
        lambda: "test-key",
    )

    monkeypatch.setattr(
        extract,
        "get_candles",
        lambda *args: calls.append(1) or response,
    )

    result = extract.extract_candles(
        "https://api.test",
        "AAPL",
        "1y",
        cache_dir=tmp_path,
    )

    assert result == response
    assert calls == [1]


def test_cached_response_avoids_api(tmp_path, monkeypatch):
    calls = []
    response = {
        "symbol": "AAPL",
        "candles": [],
    }

    monkeypatch.setattr(
        extract,
        "get_api_key",
        lambda: "test-key",
    )

    monkeypatch.setattr(
        extract,
        "get_candles",
        lambda *args: calls.append(1) or response,
    )

    extract.extract_candles(
        "https://api.test",
        "AAPL",
        "1y",
        cache_dir=tmp_path,
    )

    extract.extract_candles(
        "https://api.test",
        "AAPL",
        "1y",
        cache_dir=tmp_path,
    )

    assert calls == [1]


def test_batch_continues_after_failure(
    monkeypatch,
    tmp_path,
):
    completed = []

    def fake_run(
        base_url,
        database_path,
        symbol,
        range_name,
    ):
        if symbol == "BAD.NS":
            raise ClientHTTPError(
                "API request failed"
            )

        completed.append(symbol)

    monkeypatch.setattr(
        "trading_analytics.pipeline.run_pipeline",
        fake_run,
    )

    run_symbols(
        "https://api.test",
        tmp_path,
        ["BAD.NS", "GOOD.NS"],
        "1y",
    )

    assert completed == ["GOOD.NS"]