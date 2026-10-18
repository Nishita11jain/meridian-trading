import requests
import pytest

from trading_analytics import client


class Response:
    def __init__(self, status_code=200, payload=None, headers=None):
        self.status_code = status_code
        self.payload = payload
        self.headers = headers or {}
        self.text = "response body"

    def raise_for_status(self):
        if self.status_code >= 500:
            raise requests.HTTPError(
                f"HTTP {self.status_code}"
            )

    def json(self):
        return self.payload


def test_rate_limit(monkeypatch, caplog):
    monkeypatch.setattr(
        client.requests,
        "get",
        lambda *args, **kwargs: Response(
            429,
            headers={"Retry-After": "60"},
        ),
    )

    with pytest.raises(
        client.RateLimitError,
        match="Retry-After: 60",
    ):
        client.get_candles(
            "https://api.test",
            "secret-key",
            "INFY.NS",
            "1y",
        )

    assert "rate limit" in caplog.text
    assert "secret-key" not in caplog.text


def test_invalid_symbol(monkeypatch):
    monkeypatch.setattr(
        client.requests,
        "get",
        lambda *args, **kwargs: Response(404),
    )

    with pytest.raises(client.ClientHTTPError):
        client.get_candles(
            "https://api.test",
            "secret-key",
            "MISSING.NS",
            "1y",
        )


def test_timeout_retries(monkeypatch):
    calls = []

    def request(*args, **kwargs):
        calls.append(1)
        raise requests.Timeout()

    monkeypatch.setattr(
        client.requests,
        "get",
        request,
    )

    monkeypatch.setattr(
        client.time,
        "sleep",
        lambda _: None,
    )

    with pytest.raises(
        client.RetryableNetworkError
    ):
        client.get_candles(
            "https://api.test",
            "secret-key",
            "INFY.NS",
            "1y",
        )

    assert len(calls) == 3


def test_success_returns_payload(monkeypatch):
    payload = {
        "data": {
            "symbol": "INFY.NS",
            "candles": [],
        }
    }

    monkeypatch.setattr(
        client.requests,
        "get",
        lambda *args, **kwargs: Response(
            payload=payload
        ),
    )

    result = client.get_candles(
        "https://api.test",
        "secret-key",
        "INFY.NS",
        "1y",
    )

    assert result is payload


def test_get_api_key_reads_env(monkeypatch):
    monkeypatch.setenv("FAUXNANCE_API_KEY", "env-key")
    assert client.get_api_key() == "env-key"


def test_check_health_and_usage(monkeypatch):
    class OK:
        status_code = 200
        headers = {}

        def json(self):
            return {"ok": True}

    monkeypatch.setattr(client.requests, "get", lambda *a, **k: OK())

    assert client.check_health("https://api.test") == {"ok": True}
    assert client.check_usage("https://api.test", "key") == {"ok": True}