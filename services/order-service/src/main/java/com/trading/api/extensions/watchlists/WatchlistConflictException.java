package com.trading.api.extensions.watchlists;

import com.trading.domain.exception.DomainException;

/** A duplicate within the account: a watchlist name already used, or a symbol already on the list. */
public final class WatchlistConflictException extends DomainException {
    public WatchlistConflictException(String message) {
        super("WL-409", message);
    }
}
