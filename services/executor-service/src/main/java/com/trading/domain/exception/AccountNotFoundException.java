package com.trading.domain.exception;

public final class AccountNotFoundException extends DomainException {
    public AccountNotFoundException(String message) {
        super("ACC-404", message);
    }
}