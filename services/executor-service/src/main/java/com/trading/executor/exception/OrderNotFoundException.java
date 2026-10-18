package com.trading.executor.exception;

import java.util.UUID;

/**
 * Thrown when an order referenced in an ORDER_PLACED message is not found in the database.
 * This is a permanent failure: the order should have been inserted when the REST API accepted it.
 * A missing order indicates data inconsistency or a bug in the REST service.
 * This exception should result in immediate dead-lettering.
 */
public class OrderNotFoundException extends RuntimeException {

	private final UUID orderId;

	public OrderNotFoundException(UUID orderId) {
		super("Order not found: " + orderId);
		this.orderId = orderId;
	}

	public UUID getOrderId() {
		return orderId;
	}
}
