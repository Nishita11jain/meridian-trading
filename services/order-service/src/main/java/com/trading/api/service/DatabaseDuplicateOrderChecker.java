package com.trading.api.service;

import com.trading.domain.DuplicateOrderChecker;
import com.trading.api.mapper.OrderMapper;

/**
 * Database-backed implementation of DuplicateOrderChecker.
 * Queries the orders table via MyBatis to detect duplicate idempotency keys.
 */
public class DatabaseDuplicateOrderChecker implements DuplicateOrderChecker {

    private final OrderMapper orderMapper;

    public DatabaseDuplicateOrderChecker(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    public boolean isDuplicate(String idempotencyKey) {
        return orderMapper.existsByIdempotencyKey(idempotencyKey);
    }
}
