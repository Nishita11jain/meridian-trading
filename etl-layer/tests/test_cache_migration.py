"""Test cache migration from old range-specific format to new symbol-based format."""

import json
from pathlib import Path

from trading_analytics.migrate_cache import migrate_cache, _extract_symbol


def test_extract_symbol_from_old_format():
    """Test symbol extraction from old cache file names."""
    assert _extract_symbol("AAPL_1y.json") == "AAPL"
    assert _extract_symbol("INFY.NS_6y.json") == "INFY.NS"
    assert _extract_symbol("RELIANCE.BO_max.json") == "RELIANCE.BO"
    assert _extract_symbol("SPY_3m.json") == "SPY"
    assert _extract_symbol("QQQ_1w.json") == "QQQ"
    assert _extract_symbol("IVV_5d.json") == "IVV"


def test_extract_symbol_rejects_new_format():
    """Test that symbol extraction rejects new format (no range)."""
    assert _extract_symbol("AAPL.json") is None
    assert _extract_symbol("INFY.NS.json") is None


def test_extract_symbol_rejects_invalid():
    """Test that symbol extraction rejects invalid formats."""
    assert _extract_symbol("not_a_symbol") is None
    assert _extract_symbol("AAPL_invalid.json") is None
    assert _extract_symbol("AAPL.json.bak") is None


def test_migrate_empty_cache_dir():
    """Test migration on empty cache directory."""
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        cache_dir = Path(tmp)
        report = migrate_cache(cache_dir)
        
        assert report["migrated"] == 0
        assert report["errors"] == 0
        assert report["symbols_migrated"] == 0


def test_migrate_single_file_per_symbol():
    """Test migration when each symbol has single cache file."""
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        cache_dir = Path(tmp)
        
        # Create old-format cache files
        response_aapl = {"symbol": "AAPL", "candles": [{"date": "2026-01-01"}]}
        response_msft = {"symbol": "MSFT", "candles": [{"date": "2026-01-01"}]}
        
        (cache_dir / "AAPL_1y.json").write_text(json.dumps(response_aapl))
        (cache_dir / "MSFT_6y.json").write_text(json.dumps(response_msft))
        
        # Migrate
        report = migrate_cache(cache_dir)
        
        # Verify migration
        assert report["migrated"] == 2
        assert report["symbols_migrated"] == 2
        assert (cache_dir / "AAPL.json").exists()
        assert (cache_dir / "MSFT.json").exists()
        assert not (cache_dir / "AAPL_1y.json").exists()
        assert not (cache_dir / "MSFT_6y.json").exists()


def test_migrate_multiple_files_per_symbol():
    """Test migration when symbol has multiple range-specific files."""
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        cache_dir = Path(tmp)
        
        # Create multiple cache files for same symbol (with different counts)
        response_1y = {
            "symbol": "AAPL",
            "candles": [{"date": f"2025-01-{i:02d}"} for i in range(1, 11)]  # 10 candles
        }
        response_6y = {
            "symbol": "AAPL",
            "candles": [{"date": f"2020-01-{(i%28)+1:02d}"} for i in range(100)]  # 100 candles
        }
        
        (cache_dir / "AAPL_1y.json").write_text(json.dumps(response_1y))
        (cache_dir / "AAPL_6y.json").write_text(json.dumps(response_6y))
        
        # Migrate
        report = migrate_cache(cache_dir)
        
        # Verify consolidation
        assert report["migrated"] == 1
        assert report["symbols_migrated"] == 1
        assert (cache_dir / "AAPL.json").exists()
        
        # Should consolidate to the file with most candles (6y)
        migrated_data = json.loads((cache_dir / "AAPL.json").read_text())
        assert len(migrated_data["candles"]) == 100
        
        # Old files should be in backup
        backup_dir = cache_dir / "backup"
        assert backup_dir.exists()
        assert any("AAPL_1y.json" in f.name for f in backup_dir.rglob("*"))


def test_migrate_skips_new_format_files():
    """Test that migration skips already-migrated (new format) files."""
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        cache_dir = Path(tmp)
        
        # Create mix of old and new format files
        response = {"symbol": "AAPL", "candles": []}
        (cache_dir / "AAPL_1y.json").write_text(json.dumps(response))
        (cache_dir / "MSFT.json").write_text(json.dumps(response))
        
        # Migrate
        report = migrate_cache(cache_dir)
        
        # Should only migrate old format, skip new format
        assert report["migrated"] == 1
        assert report["skipped"] == 1
        assert report["symbols_migrated"] == 1


def test_migrate_creates_backup():
    """Test that migration creates backup of old files."""
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        cache_dir = Path(tmp)
        
        response = {"symbol": "AAPL", "candles": []}
        (cache_dir / "AAPL_1y.json").write_text(json.dumps(response))
        (cache_dir / "AAPL_6y.json").write_text(json.dumps(response))
        
        # Migrate
        report = migrate_cache(cache_dir)
        
        # Verify backup directory exists
        assert report["backup_location"]
        backup_dir = Path(report["backup_location"])
        assert backup_dir.exists()
        assert len(list(backup_dir.glob("*.json"))) > 0


def test_migrate_handles_missing_cache_dir():
    """Test migration gracefully handles missing cache directory."""
    report = migrate_cache(Path("/nonexistent/cache/dir"))
    
    assert report["migrated"] == 0
    assert report["errors"] == 0
    assert "not found" in report["message"].lower() or "does not exist" in report["message"].lower()
