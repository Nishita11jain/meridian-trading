package com.trading.api.service;

import com.trading.api.dto.OrderHistoryEntry;
import com.trading.api.dto.OrderResponse;
import com.trading.domain.OrderStatus;
import com.trading.domain.PlaceOrderRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OrderService {

    OrderResponse placeOrder(PlaceOrderRequest request, Long tokenAccountId);

    OrderResponse cancelOrder(UUID orderId, Long tokenAccountId);

    /**
     * One order, only if it belongs to the account; empty otherwise. In-process, for other modules (the
     * notification detail view) to read an order through the layer that owns orders. Not an HTTP route.
     */
    java.util.Optional<OrderHistoryEntry> findOwnOrder(UUID orderId, long accountId);

    List<OrderHistoryEntry> getOrderHistory(Long accountId, Long tokenAccountId, OrderStatus status, Instant from, Instant to);
}