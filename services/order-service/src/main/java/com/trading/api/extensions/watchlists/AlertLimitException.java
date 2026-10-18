package com.trading.api.extensions.watchlists;

import com.trading.domain.exception.DomainException;

/**
 * The account already holds the maximum number of active alerts. A cap stops one account filling the
 * table and turning the market-data consumer -- which runs inside the service that places orders --
 * into the thing that takes the platform down.
 */
public final class AlertLimitException extends DomainException {
    public AlertLimitException(String message) {
        super("LIMIT-429", message);
    }
}
