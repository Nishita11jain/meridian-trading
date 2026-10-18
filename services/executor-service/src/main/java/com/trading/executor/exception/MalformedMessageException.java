package com.trading.executor.exception;

/**
 * Thrown when a message on orders cannot be parsed as an ORDER_PLACED event.
 * The Jackson failure is kept as the cause so the failure classifier can see
 * exactly what went wrong. This is a permanent failure: the same bytes will
 * never parse later, so the message is dead-lettered on the first attempt.
 */
public class MalformedMessageException extends RuntimeException {

	public MalformedMessageException(String message, Throwable cause) {
		super(message, cause);
	}
}
