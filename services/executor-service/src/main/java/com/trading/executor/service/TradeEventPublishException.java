package com.trading.executor.service;

/**
 * Thrown when an order has already been settled (its status, cash and position are committed) but the
 * outcome could not be published to trade-events, even after retrying in place. The outcome is kept by
 * OrderExecutionService and is published on the next delivery of the same ORDER_PLACED message, so the
 * consumer treats this as transient and retries rather than acknowledging. If its retries are spent
 * the message is dead-lettered under the reason "trade-event-publish-failed", which tells an operator
 * that money moved but the event did not go out.
 */
public class TradeEventPublishException extends RuntimeException {

	public TradeEventPublishException(String message, Throwable cause) {
		super(message, cause);
	}
}
