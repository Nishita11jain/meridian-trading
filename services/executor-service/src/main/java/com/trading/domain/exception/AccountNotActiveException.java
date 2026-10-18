package com.trading.domain.exception;

public final class AccountNotActiveException extends DomainException {
    public AccountNotActiveException(String message) {
        super("ACC-403", message);
    }
}