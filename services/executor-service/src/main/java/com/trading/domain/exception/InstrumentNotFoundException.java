package com.trading.domain.exception;

public final class InstrumentNotFoundException extends DomainException {
    public InstrumentNotFoundException(String message) {
        super("INS-404", message);
    }
}