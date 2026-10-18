package com.trading.api.extensions.notifications;

import com.trading.api.dto.OrderHistoryEntry;

import java.math.BigDecimal;
import java.time.Instant;

/** The order a notification is about, as the detail view shows it. */
public record OrderDetails(
        String orderId,
        String symbol,
        String side,
        int quantity,
        BigDecimal price,
        BigDecimal executedPrice,
        String status,
        String rejectionReason,
        Instant createdOn
) {

    static OrderDetails from(OrderHistoryEntry order) {
        return new OrderDetails(order.orderId(), order.symbol(), String.valueOf(order.side()), order.quantity(),
                order.price(), order.executedPrice(), String.valueOf(order.status()), order.rejectionReason(),
                order.createdOn());
    }
}
