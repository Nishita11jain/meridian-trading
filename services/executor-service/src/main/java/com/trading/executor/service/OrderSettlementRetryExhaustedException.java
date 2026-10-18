package com.trading.executor.service;

/**
 * Thrown when OrderExecutionService exhausts its retry budget for optimistic lock failures.
 * After MAX_ATTEMPTS retries on OptimisticLockRetryException, this exception is thrown
 * to indicate that the order settlement cannot be retried further.
 */
public class OrderSettlementRetryExhaustedException extends RuntimeException {

	public OrderSettlementRetryExhaustedException(String message, Throwable cause) {
		super(message, cause);
	}
}
