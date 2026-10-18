package com.trading.api.service;

import com.trading.domain.Order;
import com.trading.domain.Position;

public interface OrderEventPublisher {

    void publishOrderPlaced(Order order);

    /**
     * @param position the account's position in the order's symbol, or null when it holds none;
     *                 the event reports the position quantity and average cost after the cancel
     */
    void publishOrderCancelled(Order order, Position position);

    /**
     * Announces an order this service refused at placement (not enough cash, or selling more than is
     * held) on trade-events, as ORDER_REJECTED, so the refusal reaches the same consumers as one the
     * executor rejected -- notifications in particular. Called after the REJECTED row has committed.
     *
     * @param position the account's position in the symbol, or null when it holds none
     */
    void publishOrderRejected(java.util.UUID orderId, com.trading.domain.PlaceOrderRequest request,
                              String reason, Position position);
}
