package com.trading.api.extensions.strategies;

import com.trading.domain.exception.DomainException;

public final class StrategyNotFoundException extends DomainException {
    public StrategyNotFoundException(String message) {
        super("STRAT-404", message);
    }
}
