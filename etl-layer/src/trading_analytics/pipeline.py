import logging
from collections.abc import Iterable
from pathlib import Path

from .client import (
    ClientHTTPError,
    RateLimitError,
    RetryableNetworkError,
)
from .extract import extract_candles
from .load import load
from .transform import transform


logger = logging.getLogger(__name__)


def run_pipeline(
    base_url: str,
    database_path: Path,
    symbol: str,
    range_name: str,
) -> None:
    """Run the Fauxnance ETL pipeline for one symbol."""

    # 1. Extract
    raw_response = extract_candles(
        base_url,
        symbol,
        range_name,
    )

    # 2. Transform
    frame = transform(
        raw_response
    )

    # 3. Load transformed data into DuckDB
    load(
        frame,
        database_path,
    )


def run_symbols(
    base_url: str,
    database_path: Path,
    symbols: Iterable[str],
    range_name: str,
) -> None:
    """Run the ETL pipeline for multiple symbols."""

    for symbol in symbols:

        try:
            run_pipeline(
                base_url,
                database_path,
                symbol,
                range_name,
            )

            logger.info(
                "Completed %s",
                symbol,
            )

        except RateLimitError as exc:

            logger.error(
                "%s: daily quota exhausted: %s",
                symbol,
                exc,
            )

            # Stop because continuing would keep hitting the API.
            raise

        except ClientHTTPError as exc:

            logger.error(
                "%s: API request failed: %s",
                symbol,
                exc,
            )

            # Continue with the next symbol.
            continue

        except RetryableNetworkError as exc:

            logger.error(
                "%s: network failure: %s",
                symbol,
                exc,
            )

            # Continue with the next symbol.
            continue

        except ValueError as exc:

            logger.error(
                "%s: invalid payload: %s",
                symbol,
                exc,
            )

            # Continue with the next symbol.
            continue