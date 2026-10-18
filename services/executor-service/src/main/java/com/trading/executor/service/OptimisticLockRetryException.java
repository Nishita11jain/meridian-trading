package com.trading.executor.service;

/**
 * Thrown when an optimistic lock failure occurs during account or position updates.
 * This is a transient error: the order should be retried after backing off.
 * After MAX_ATTEMPTS retries, this becomes OrderSettlementRetryExhaustedException.
 */
public class OptimisticLockRetryException extends RuntimeException {

	public OptimisticLockRetryException(String message) {
		super(message);
	}
}
