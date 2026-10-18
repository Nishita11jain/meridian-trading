package com.trading.executor.service;

/**
 * Thrown when a message could not be written to its dead-letter topic. The
 * consumer must not acknowledge the original message in that case: it is the
 * only remaining copy, so it is left to be redelivered.
 */
public class DeadLetterPublishException extends RuntimeException {

	public DeadLetterPublishException(String message, Throwable cause) {
		super(message, cause);
	}
}
