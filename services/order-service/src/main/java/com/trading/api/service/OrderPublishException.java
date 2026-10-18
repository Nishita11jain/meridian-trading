package com.trading.api.service;

/**
 * Thrown when ORDER_PLACED could not be delivered to the orders topic. The
 * order is already committed at NEW at that point, so callers log this and
 * leave the order for OrderPublishReconciler to replay; they do not fail the
 * request.
 */
public class OrderPublishException extends RuntimeException {

    public OrderPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
