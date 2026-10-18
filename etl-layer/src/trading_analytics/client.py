import logging
import os
import time
from pathlib import Path
from typing import Any

import requests

from .errors import (
    ClientHTTPError,
    RateLimitError,
    RetryableNetworkError,
)


logger = logging.getLogger(__name__)

MAX_TRANSPORT_ATTEMPTS = 3


try:
    from dotenv import load_dotenv
except ModuleNotFoundError:
    def load_dotenv() -> None:
        env_file = Path(".env")

        if not env_file.exists():
            return

        for line in env_file.read_text(
            encoding="utf-8"
        ).splitlines():

            line = line.strip()

            if (
                not line
                or line.startswith("#")
                or "=" not in line
            ):
                continue

            name, value = line.split("=", 1)

            os.environ.setdefault(
                name.strip(),
                value.strip().strip("\"'")
            )


load_dotenv()


def required_environment(name: str) -> str:
    value = os.getenv(name)

    if not value:
        raise RuntimeError(
            f"{name} is not set"
        )

    return value


def get_api_key() -> str:
    return required_environment(
        "FAUXNANCE_API_KEY"
    )


def get_base_url() -> str:
    return required_environment(
        "FAUXNANCE_BASE_URL"
    ).rstrip("/")


def check_health(base_url: str) -> Any:
    """Check whether Fauxnance is available."""

    return _get_json(
        f"{base_url}/health",
        timeout=10,
    )


def check_usage(
    base_url: str,
    api_key: str,
) -> Any:
    """Check Fauxnance API usage when required."""

    return _get_json(
        f"{base_url}/usage",
        timeout=10,
        headers={
            "X-Api-Key": api_key
        },
    )


def get_candles(
    base_url: str,
    api_key: str,
    symbol: str,
    range_name: str,
) -> Any:
    """
    Fetch raw candle data from Fauxnance.

    range_name is retained for the cache key.
    Fauxnance's candle endpoint does not receive
    it as a query parameter.
    """

    if not symbol:
        raise ValueError(
            "symbol must not be empty"
        )

    return _get_json(
        f"{base_url}/candles/{symbol}",
        timeout=30,
        headers={
            "X-Api-Key": api_key
        },
    )


def _get_json(
    url: str,
    timeout: int,
    **kwargs: Any,
) -> Any:

    for attempt in range(
        1,
        MAX_TRANSPORT_ATTEMPTS + 1,
    ):

        try:
            response = requests.get(
                url,
                timeout=timeout,
                **kwargs,
            )

        except (
            requests.ConnectionError,
            requests.Timeout,
        ) as exc:

            if attempt == MAX_TRANSPORT_ATTEMPTS:

                logger.error(
                    "transport failure after %s attempts: %s",
                    attempt,
                    type(exc).__name__,
                )

                raise RetryableNetworkError(
                    "Fauxnance request failed after "
                    f"{MAX_TRANSPORT_ATTEMPTS} attempts"
                ) from exc

            delay = 2 ** (attempt - 1)

            logger.warning(
                "transport failure; retry %s/%s in %ss",
                attempt,
                MAX_TRANSPORT_ATTEMPTS - 1,
                delay,
            )

            time.sleep(delay)

        else:

            if response.status_code == 429:

                retry_after = response.headers.get(
                    "Retry-After",
                    "unspecified",
                )

                logger.error(
                    "rate limit (429); Retry-After=%s",
                    retry_after,
                )

                raise RateLimitError(
                    "Fauxnance rate limit reached (429); "
                    f"Retry-After: {retry_after}"
                )

            if 400 <= response.status_code < 600:

                logger.error(
                    "Fauxnance HTTP error: %s",
                    response.status_code,
                )

                raise ClientHTTPError(
                    "Fauxnance request failed with "
                    f"HTTP {response.status_code}"
                )

            try:
                return response.json()

            except ValueError as exc:

                raise ClientHTTPError(
                    "Fauxnance returned invalid JSON"
                ) from exc

    raise RetryableNetworkError(
        "Fauxnance request failed"
    )