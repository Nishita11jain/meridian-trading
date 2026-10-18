package com.trading.domain.exception;

import java.math.BigDecimal;

public final class InsufficientFundsException extends DomainException {

    private final BigDecimal required;
    private final BigDecimal available;

    public InsufficientFundsException(String message, BigDecimal required, BigDecimal available) {
        super("ORD-400", message);
        this.required = required;
        this.available = available;
    }

    public BigDecimal getRequired() {
        return required;
    }

    public BigDecimal getAvailable() {
        return available;
    }
}