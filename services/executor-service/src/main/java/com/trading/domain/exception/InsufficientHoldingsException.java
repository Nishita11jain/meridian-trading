package com.trading.domain.exception;

public final class InsufficientHoldingsException extends DomainException {

    private final int requested;
    private final int held;

    public InsufficientHoldingsException(String message, int requested, int held) {
        super("ORD-409", message);
        this.requested = requested;
        this.held = held;
    }

    public int getRequested() {
        return requested;
    }

    public int getHeld() {
        return held;
    }
}