package com.trading.api.exception;

import com.trading.domain.exception.DomainException;

/**
 * An order for an instrument whose market is closed now. 409 MKT-409: the request is well formed, but
 * the market's state does not allow it at this moment. Nothing is stored: like an unknown instrument,
 * the order was never accepted.
 */
public final class MarketClosedException extends DomainException {

    public MarketClosedException(String message) {
        super("MKT-409", message);
    }
}
