package com.trading.executor.service;

/**
 * Publishes failed messages to dead-letter topics.
 * Original message preserved; failure reason added in headers.
 */
public interface DeadLetterPublisher {

	/**
	 * Publish a message to the dead-letter topic corresponding to its original topic.
	 *
	 * @param originalTopic source topic (e.g., "orders")
	 * @param key partition key (e.g., accountId)
	 * @param originalMessage the original message JSON
	 * @param failureReason short string for the failure-reason header
	 * @param cause the exception (logged but not sent in message)
	 */
	void publishDeadLetter(
			String originalTopic,
			String key,
			String originalMessage,
			String failureReason,
			Throwable cause
	);
}
