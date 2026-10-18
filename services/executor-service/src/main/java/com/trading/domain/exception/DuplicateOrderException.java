package com.trading.domain.exception;

public final class DuplicateOrderException extends DomainException {
    public DuplicateOrderException(String message) {
        super("ORD-409", message);
    }
}