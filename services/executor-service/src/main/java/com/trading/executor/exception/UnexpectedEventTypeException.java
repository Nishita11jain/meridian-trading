package com.trading.executor.exception;

/**
 * Thrown when an ORDER_PLACED message carries an unexpected event type.
 * This is a permanent failure: indicates a producer bug or schema version mismatch.
 * Should result in immediate dead-lettering.
 */
public class UnexpectedEventTypeException extends RuntimeException {

	private final String eventType;

	public UnexpectedEventTypeException(String eventType) {
		super("Unexpected event type: " + eventType + ". Expected: ORDER_PLACED");
		this.eventType = eventType;
	}

	public String getEventType() {
		return eventType;
	}
}
