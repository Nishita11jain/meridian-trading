package com.trading.api.extensions.strategies;

import com.trading.domain.exception.DomainException;

/** The account already holds the maximum number of strategies. Reuses LIMIT-429 like Watchlists' alert cap. */
public final class StrategyLimitException extends DomainException {
    public StrategyLimitException(String message) {
        super("LIMIT-429", message);
    }
}
