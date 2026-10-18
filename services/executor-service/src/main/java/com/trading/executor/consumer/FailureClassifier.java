package com.trading.executor.consumer;

/**
 * Strategy for classifying exceptions into failure types.
 * Determines whether to dead-letter immediately, retry with backoff, or treat as business outcome.
 */
public interface FailureClassifier {

	/**
	 * Classify an exception encountered during order execution.
	 *
	 * @param exception the exception that was thrown
	 * @param attemptNumber the attempt number (1-based) when this exception occurred
	 * @return the classification of this failure
	 */
	FailureType classify(Throwable exception, int attemptNumber);

	/**
	 * Get a user-friendly failure reason for dead-lettering.
	 *
	 * @param exception the exception that was thrown
	 * @return a short string suitable for a message header (e.g., "malformed-json", "order-not-found")
	 */
	String getFailureReason(Throwable exception);
}
