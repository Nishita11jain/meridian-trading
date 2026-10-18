package com.trading.executor.consumer;

/**
 * Classifies failures into categories to determine handling strategy:
 * - PERMANENT: Dead-letter immediately (poison message)
 * - TRANSIENT: Retry with backoff (will likely succeed later)
 * - BUSINESS_OUTCOME: Not a failure (e.g., order rejected by pricing rules)
 */
public enum FailureType {
	PERMANENT,
	TRANSIENT,
	BUSINESS_OUTCOME
}
