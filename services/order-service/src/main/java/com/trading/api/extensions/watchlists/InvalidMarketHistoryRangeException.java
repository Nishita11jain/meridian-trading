package com.trading.api.extensions.watchlists;

import com.trading.domain.exception.DomainException;

/** A chart-history range that is not one of 5D, 1M, 3M, 6M or 1Y (422 VAL-422). */
public final class InvalidMarketHistoryRangeException extends DomainException {
    public InvalidMarketHistoryRangeException(String message) {
        super("VAL-422", message);
    }
}
