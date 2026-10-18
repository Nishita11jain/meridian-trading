"""
Migrate cache files from old range-specific format to new symbol-based format.

Old format: INFY.NS_1y.json, INFY.NS_6y.json, etc.
New format: INFY.NS.json

Logic:
1. Find all old-format cache files
2. Group by symbol
3. For each symbol, consolidate into single file (choose most complete dataset)
4. Move old files to backup directory for safety
"""

import json
import logging
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional


logger = logging.getLogger(__name__)


def _count_candles(file_path: Path) -> int:
    """Count candles in a cache file."""
    try:
        data = json.loads(file_path.read_text(encoding="utf-8"))
        if isinstance(data, dict):
            candles = data.get("data", {}).get("candles", data.get("candles", []))
        else:
            candles = data if isinstance(data, list) else []
        return len(candles) if isinstance(candles, list) else 0
    except Exception:
        return 0


def _get_date_range(file_path: Path) -> tuple:
    """Get min and max dates in a cache file."""
    try:
        data = json.loads(file_path.read_text(encoding="utf-8"))
        if isinstance(data, dict):
            candles = data.get("data", {}).get("candles", data.get("candles", []))
        else:
            candles = data if isinstance(data, list) else []
        
        if not isinstance(candles, list) or not candles:
            return None, None
        
        dates = []
        for candle in candles:
            if isinstance(candle, dict) and "date" in candle:
                dates.append(candle["date"])
        
        if dates:
            dates.sort()
            return dates[0], dates[-1]
    except Exception:
        pass
    
    return None, None


def _select_best_file(file_paths: List[Path]) -> Path:
    """
    Select the best cache file (most complete dataset).
    
    Preference order:
    1. Most candles (longest date range)
    2. Most recent file timestamp
    """
    if not file_paths:
        return None
    
    # Score each file by candle count
    scores = {}
    for fpath in file_paths:
        count = _count_candles(fpath)
        scores[fpath] = count
    
    best = max(file_paths, key=lambda fp: scores[fp])
    logger.debug(
        f"Selected {best.name} with {scores[best]} candles "
        f"(from {len(file_paths)} candidates)"
    )
    return best


def _extract_symbol(filename: str) -> Optional[str]:
    """
    Extract symbol from filename.
    
    Old format: INFY.NS_1y.json, INFY.NS_6y.json, etc.
    Returns: "INFY.NS" (everything before the last underscore and extension)
    """
    # Remove .json extension
    if not filename.endswith(".json"):
        return None
    
    base = filename[:-5]  # Remove .json
    
    # Check if it matches old format: has underscore followed by range pattern
    # e.g., "INFY.NS_1y", "INFY.NS_6y", "RELIANCE.NS_max"
    parts = base.rsplit("_", 1)
    if len(parts) != 2:
        return None
    
    symbol, range_part = parts
    
    # Validate range_part is a valid range (1-3 digits + unit or "max")
    if not (range_part in ["max"] or (range_part[:-1].isdigit() and range_part[-1] in "ymwd")):
        return None
    
    return symbol


def migrate_cache(cache_dir: Path = Path("data/cache")) -> Dict[str, dict]:
    """
    Migrate old-format cache files to new symbol-based format.
    
    Args:
        cache_dir: Cache directory to migrate
    
    Returns:
        Migration report with statistics
    """
    if not cache_dir.exists():
        logger.info(f"Cache directory does not exist: {cache_dir}")
        return {
            "migrated": 0,
            "skipped": 0,
            "errors": 0,
            "symbols_migrated": 0,
            "message": "Cache directory not found",
        }
    
    # Find all .json files
    json_files = list(cache_dir.glob("*.json"))
    if not json_files:
        logger.info("No cache files found")
        return {
            "migrated": 0,
            "skipped": 0,
            "errors": 0,
            "symbols_migrated": 0,
            "message": "No cache files found",
        }
    
    # Group by symbol
    symbol_files: Dict[str, List[Path]] = {}
    new_format_files = []
    
    for json_file in json_files:
        symbol = _extract_symbol(json_file.name)
        
        if symbol is None:
            # Could be new format (INFY.NS.json) or unrelated file
            if "_" not in json_file.stem or json_file.stem.endswith(json_file.name[:-5]):
                new_format_files.append(json_file)
            continue
        
        if symbol not in symbol_files:
            symbol_files[symbol] = []
        symbol_files[symbol].append(json_file)
    
    if not symbol_files:
        logger.info("No old-format cache files found (already migrated?)")
        return {
            "migrated": 0,
            "skipped": len(new_format_files),
            "errors": 0,
            "message": f"Already migrated ({len(new_format_files)} new-format files found)",
        }
    
    # Create backup directory
    backup_dir = cache_dir / "backup" / datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_dir.mkdir(parents=True, exist_ok=True)
    
    # Migrate each symbol
    migrated_count = 0
    error_count = 0
    
    for symbol, files in sorted(symbol_files.items()):
        if len(files) == 1:
            # Single file: just rename
            old_file = files[0]
            new_file = cache_dir / f"{symbol}.json"
            
            if new_file.exists():
                logger.warning(f"Target {new_file.name} already exists, skipping {old_file.name}")
                # Move old file to backup anyway
                try:
                    old_file.rename(backup_dir / old_file.name)
                    logger.debug(f"Backed up {old_file.name}")
                except Exception as e:
                    logger.error(f"Error backing up {old_file.name}: {e}")
                    error_count += 1
                continue
            
            try:
                old_file.rename(new_file)
                logger.info(f"Migrated {old_file.name} → {new_file.name}")
                migrated_count += 1
            except Exception as e:
                logger.error(f"Error migrating {old_file.name}: {e}")
                error_count += 1
        
        else:
            # Multiple files: consolidate to best one
            best_file = _select_best_file(files)
            new_file = cache_dir / f"{symbol}.json"
            
            if new_file.exists():
                logger.warning(f"Target {new_file.name} already exists, skipping consolidation")
                error_count += 1
                continue
            
            try:
                # Copy best file to new location
                best_file.rename(new_file)
                logger.info(
                    f"Consolidated {len(files)} files → {new_file.name} "
                    f"(selected {best_file.name} with {_count_candles(new_file)} candles)"
                )
                migrated_count += 1
                
                # Move other files to backup
                for old_file in files:
                    if old_file.exists():
                        try:
                            old_file.rename(backup_dir / old_file.name)
                            logger.debug(f"Backed up {old_file.name}")
                        except Exception as e:
                            logger.error(f"Error backing up {old_file.name}: {e}")
                            error_count += 1
            
            except Exception as e:
                logger.error(f"Error consolidating files for {symbol}: {e}")
                error_count += 1
    
    report = {
        "migrated": migrated_count,
        "skipped": len(new_format_files),
        "errors": error_count,
        "symbols_migrated": len(symbol_files),
        "backup_location": str(backup_dir),
        "message": f"Migrated {migrated_count} symbols, backed up to {backup_dir}",
    }
    
    logger.info(f"Migration complete: {report['message']}")
    return report


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    report = migrate_cache()
    print(json.dumps(report, indent=2))
