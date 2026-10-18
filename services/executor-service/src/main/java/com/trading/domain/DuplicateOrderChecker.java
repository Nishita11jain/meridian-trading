package com.trading.domain;


public interface DuplicateOrderChecker {

    boolean isDuplicate(String idempotencyKey);
}
