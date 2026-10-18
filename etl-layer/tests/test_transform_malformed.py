import json
from pathlib import Path

import pytest

from trading_analytics.transform import transform


@pytest.fixture
def malformed_response():
    fixture_path = Path(__file__).parent / "fixtures" / "candles-malformed.json"
    return json.loads(fixture_path.read_text())


def test_drops_both_occurrences_of_a_duplicate_date(malformed_response):
    frame = transform(malformed_response)
    assert "2026-07-01" not in frame["date"].dt.strftime("%Y-%m-%d").values


def test_drops_a_row_missing_a_required_field(malformed_response):
    frame = transform(malformed_response)
    assert "2026-07-02" not in frame["date"].dt.strftime("%Y-%m-%d").values


def test_drops_a_non_numeric_value(malformed_response):
    frame = transform(malformed_response)
    assert "2026-07-06" not in frame["date"].dt.strftime("%Y-%m-%d").values


def test_rejects_a_high_below_a_low(malformed_response):
    frame = transform(malformed_response)
    assert "2026-07-07" not in frame["date"].dt.strftime("%Y-%m-%d").values


def test_flags_but_keeps_a_negative_volume(malformed_response):
    frame = transform(malformed_response)
    row = frame[frame["date"].dt.strftime("%Y-%m-%d") == "2026-07-08"]
    assert len(row) == 1
    assert bool(row.iloc[0]["volume_flagged"]) is True


def test_refuses_a_non_iso_date(malformed_response):
    frame = transform(malformed_response)
    # The only remaining valid, unique, well-formed candle in the fixture.
    assert len(frame) == 1