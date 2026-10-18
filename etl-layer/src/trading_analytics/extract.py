import logging
import re
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Optional

from .cache import load_cached_response, save_raw_response
from .client import get_api_key, get_candles
from .migrate_cache import migrate_cache

DEFAULT_CACHE_DIR = Path("data/cache")

logger = logging.getLogger(__name__)

# Module-level flag to run migration only once per session
_migration_done = False

# Regex to parse range_name like "1y", "6m", "3d", "max"
RANGE_PATTERN = re.compile(r"^(\d+)([ymwd])?$|^max$", re.IGNORECASE)


def _parse_range_name(range_name: str) -> Optional[timedelta]:
    """
    Parse range_name into a timedelta.
    
    Examples:
        "1y" -> timedelta(days=365)
        "6m" -> timedelta(days=180)
        "max" -> None (all data)
    
    Args:
        range_name: Time range string
    
    Returns:
        timedelta to subtract from max date, or None if "max"
    """
    if not range_name:
        return None
    
    range_lower = range_name.lower()
    
    if range_lower == "max":
        return None
    
    match = RANGE_PATTERN.match(range_lower)
    if not match:
        logger.warning(f"Invalid range_name format: {range_name}, defaulting to all data")
        return None
    
    quantity = int(match.group(1))
    unit = match.group(2) or "y"  # Default to years if not specified
    
    if unit == "y":
        days = quantity * 365
    elif unit == "m":
        days = quantity * 30
    elif unit == "w":
        days = quantity * 7
    elif unit == "d":
        days = quantity
    else:
        days = quantity * 365  # Fallback to years
    
    return timedelta(days=days)


def filter_candles_by_range(
    raw_response: Any,
    range_name: str,
) -> Any:
    """
    Filter candles data by date range.
    
    The API returns all available historical data regardless of range_name.
    This function extracts only the candles within the requested time range.
    
    Logic:
    1. Find the maximum (most recent) date in the candles
    2. Calculate the cutoff date based on range_name
    3. Filter candles to only include dates >= cutoff_date
    
    Args:
        raw_response: Full API response (with all candles)
        range_name: Time range filter (e.g., "1y", "6y", "max")
    
    Returns:
        Modified response with filtered candles
    """
    # Handle empty or null responses
    if not raw_response:
        return raw_response
    
    # Extract candles from response (handle both dict and list formats)
    if isinstance(raw_response, dict):
        data = raw_response.get("data", raw_response)
        if not isinstance(data, dict):
            return raw_response
        candles = data.get("candles", [])
    elif isinstance(raw_response, list):
        candles = raw_response
    else:
        return raw_response
    
    if not isinstance(candles, list) or not candles:
        return raw_response
    
    # Parse the range
    delta = _parse_range_name(range_name)
    if delta is None:
        # "max" or unrecognized format: return all data
        return raw_response
    
    # Find max date in candles
    max_date = None
    for candle in candles:
        if isinstance(candle, dict):
            date_str = candle.get("date")
            if isinstance(date_str, str):
                try:
                    date = datetime.strptime(date_str, "%Y-%m-%d").date()
                    if max_date is None or date > max_date:
                        max_date = date
                except (ValueError, AttributeError):
                    pass
    
    if max_date is None:
        # No valid dates found, return all data
        return raw_response
    
    # Calculate cutoff date
    cutoff_date = max_date - delta
    
    # Filter candles
    filtered_candles = [
        candle
        for candle in candles
        if isinstance(candle, dict)
        and isinstance(candle.get("date"), str)
        and datetime.strptime(candle.get("date"), "%Y-%m-%d").date() >= cutoff_date
    ]
    
    logger.debug(f"Filtered candles: {len(candles)} → {len(filtered_candles)} for range {range_name}")
    
    # Return filtered response in same format
    if isinstance(raw_response, dict):
        data = raw_response.get("data", raw_response)
        if isinstance(data, dict) and "candles" in data:
            data["candles"] = filtered_candles
        return raw_response
    else:
        return filtered_candles


def extract_candles(
    base_url: str,
    symbol: str,
    range_name: str,
    cache_dir: Path = DEFAULT_CACHE_DIR,
) -> Any:
    """
    Extract candle data for a symbol, using cache when available.
    
    Logic:
    1. Check if full dataset is cached (symbol-based key, no range)
    2. If cache hit: return cached data filtered by range_name
    3. If cache miss: fetch from API, cache full dataset, filter by range
    
    This approach enables cross-range reuse: 6y cache serves 1y requests.
    
    Args:
        base_url: API base URL
        symbol: Trading symbol (e.g., "INFY.NS")
        range_name: Time range filter (e.g., "1y", "6y", "max")
        cache_dir: Directory for cache storage
    
    Returns:
        Candle data filtered to requested range
    """
    
    # Run cache migration once per session (on first extract_candles call)
    global _migration_done
    if not _migration_done:
        try:
            migrate_cache(cache_dir)
            _migration_done = True
        except Exception as e:
            logger.warning(f"Cache migration failed (non-fatal): {e}")
    
    # Try to load full cached dataset (symbol-based key, no range)
    cached = load_cached_response(
        cache_dir,
        symbol,
        range_name=None,  # Load full dataset
    )
    
    if cached is not None:
        logger.info(f"Cache hit for {symbol} (filtered to {range_name})")
        # Filter the cached full dataset to requested range
        return filter_candles_by_range(cached, range_name)
    
    logger.info(f"Cache miss for {symbol}, fetching from API")
    
    api_key = get_api_key()
    if not api_key:
        raise ValueError("FAUXNANCE_API_KEY is not configured")
    
    # Fetch full dataset from API (range_name is ignored by API)
    raw_response = get_candles(
        base_url,
        api_key,
        symbol,
        range_name,
    )
    
    # Cache the full dataset (not range-filtered)
    save_raw_response(
        cache_dir,
        symbol,
        range_name=None,  # Save full dataset with symbol-based key
        response=raw_response,
    )
    
    # Filter to requested range before returning
    return filter_candles_by_range(raw_response, range_name)