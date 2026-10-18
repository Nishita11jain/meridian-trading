import json
import logging
import time
from pathlib import Path


logger = logging.getLogger(__name__)

# TTL for cache in seconds (24 hours)
DEFAULT_TTL_SECONDS = 86400


def is_cache_expired(file_path: Path, ttl_seconds: int = DEFAULT_TTL_SECONDS) -> bool:
    """
    Check if a cache file has expired based on its modification time.
    
    Args:
        file_path: Path to the cache file
        ttl_seconds: Time-to-live in seconds (default: 24 hours)
    
    Returns:
        True if file is older than TTL, False otherwise
    """
    if not file_path.exists():
        return True
    
    mtime = file_path.stat().st_mtime
    current_time = time.time()
    age_seconds = current_time - mtime
    
    expired = age_seconds > ttl_seconds
    if expired:
        logger.info(f"Cache expired for {file_path.name}: {age_seconds:.0f}s > {ttl_seconds}s TTL")
    
    return expired


def save_raw_response(
    cache_dir: Path,
    symbol: str,
    range_name: str,
    response,
) -> Path:
    """
    Save raw API response to cache.
    
    Note: range_name is accepted for backward compatibility but not used in cache key.
    Cache key is now symbol-based only: {symbol}.json
    This allows cross-range data reuse (e.g., 6y data serves 1y requests).
    
    Args:
        cache_dir: Directory to store cache files
        symbol: Trading symbol (e.g., "INFY.NS")
        range_name: Time range (kept for API compatibility, not used in cache key)
        response: API response data to cache
    
    Returns:
        Path to the cached file
    """
    cache_dir.mkdir(
        parents=True,
        exist_ok=True,
    )

    # Cache key: symbol only (no range) to enable cross-range reuse
    path = cache_dir / f"{symbol.upper()}.json"

    path.write_text(
        json.dumps(response, indent=2),
        encoding="utf-8",
    )

    logger.info(f"Saved cache for {symbol}: {path}")
    return path


def load_cached_response(
    cache_dir: Path,
    symbol: str,
    range_name: str = None,
) -> dict:
    """
    Load cached API response with TTL support.
    
    Uses disk-based caching with configurable time-to-live. Each load checks
    if the cached file has expired based on its modification time.
    
    Args:
        cache_dir: Directory where cache files are stored
        symbol: Trading symbol (e.g., "INFY.NS")
        range_name: Time range (accepted for backward compatibility, not used)
    
    Returns:
        Cached response data (dict) or None if not found/expired
    """
    cache_dir = Path(cache_dir)  # Ensure Path object
    path = cache_dir / f"{symbol.upper()}.json"

    if not path.exists():
        logger.debug(f"Cache miss for {symbol}: file not found")
        return None

    if is_cache_expired(path):
        logger.info(f"Cache expired for {symbol}")
        return None

    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        logger.debug(f"Cache hit for {symbol}")
        return data
    except (json.JSONDecodeError, IOError) as e:
        logger.error(f"Error reading cache for {symbol}: {e}")
        return None