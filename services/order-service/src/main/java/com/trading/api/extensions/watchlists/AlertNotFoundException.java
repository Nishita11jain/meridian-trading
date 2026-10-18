package com.trading.api.extensions.watchlists;

import com.trading.domain.exception.DomainException;

/** A price alert that does not exist, or is not owned by the caller. */
public final class AlertNotFoundException extends DomainException {
    public AlertNotFoundException(String message) {
        super("ALERT-404", message);
    }
}
