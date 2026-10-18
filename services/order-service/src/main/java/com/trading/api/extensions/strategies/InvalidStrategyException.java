package com.trading.api.extensions.strategies;

import com.trading.domain.exception.DomainException;

public final class InvalidStrategyException extends DomainException {
    public InvalidStrategyException(String message) {
        super("VAL-422", message);
    }
}
