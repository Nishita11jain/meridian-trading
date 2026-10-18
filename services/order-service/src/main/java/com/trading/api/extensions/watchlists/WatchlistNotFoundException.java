package com.trading.api.extensions.watchlists;

import com.trading.domain.exception.DomainException;

/** A watchlist that does not exist, or is not owned by the caller. 404 either way, so ownership is not leaked. */
public final class WatchlistNotFoundException extends DomainException {
    public WatchlistNotFoundException(String message) {
        super("WL-404", message);
    }
}
