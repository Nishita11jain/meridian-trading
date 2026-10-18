package com.trading.domain.exception;

public final class InvalidOrderParametersException extends DomainException {
    public InvalidOrderParametersException(String message) {
        super("VAL-422", message);
    }
}