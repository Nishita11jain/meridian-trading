package com.trading.api.event;

import com.trading.domain.OrderSide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * trade-events payload exactly as contracts/event-schemas/kafka-topics.md fixes it, for
 * ORDER_FILLED, ORDER_REJECTED and ORDER_CANCELLED alike. This service
 * publishes ORDER_CANCELLED directly (a cancel never reaches the executor);
 * the executor's own, identical copy of this record is what it uses to
 * publish ORDER_FILLED/ORDER_REJECTED.
 */
public record TradeEventPayload(
        UUID orderId,
        Long accountId,
        String symbol,
        OrderSide side,
        long quantity,
        BigDecimal price,
        BigDecimal executedPrice,
        String status,
        String reason,
        BigDecimal cashDelta,
        Integer positionQuantityAfter,
        BigDecimal averageCostAfter,
        Instant executedOn
) {
}
