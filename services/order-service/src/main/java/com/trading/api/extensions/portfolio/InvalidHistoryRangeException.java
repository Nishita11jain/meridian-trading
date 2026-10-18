package com.trading.api.extensions.portfolio;

import com.trading.domain.exception.DomainException;

/** A history range that is not one of 1D, 1W, 1M or ALL (422 VAL-422). */
public final class InvalidHistoryRangeException extends DomainException {
    public InvalidHistoryRangeException(String message) {
        super("VAL-422", message);
    }
}
