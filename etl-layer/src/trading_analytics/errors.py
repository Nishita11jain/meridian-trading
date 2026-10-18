class AnalyticsError(Exception):
    """Base exception for analytics."""


class RateLimitError(AnalyticsError):
    """Fauxnance returned HTTP 429."""


class ClientHTTPError(AnalyticsError):
    """Fauxnance returned an HTTP error."""


class RetryableNetworkError(AnalyticsError):
    """Network request failed after retries."""


class MalformedPayloadError(AnalyticsError, ValueError):
    """Fauxnance returned invalid data."""