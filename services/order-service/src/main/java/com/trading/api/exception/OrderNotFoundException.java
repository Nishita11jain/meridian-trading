package com.trading.api.exception;

/**
 * No order exists with the identifier that was asked for.
 *
 * <p>contracts/api-schemas/trade-api.yaml answers this on DELETE /api/v1/orders/{id} with HTTP 404 and the
 * catalogue code ORD-409 ("Order not found"): the catalogue has no ORD-404, so the code stays
 * ORD-409 and the status is what tells the caller the order does not exist, as opposed to a 409
 * for an order that exists but is no longer cancellable.
 */
public class OrderNotFoundException extends RuntimeException {

    public static final String CODE = "ORD-409";

    public OrderNotFoundException(String message) {
        super(message);
    }
}
