package com.trading.api.event;

import com.trading.domain.OrderSide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * trade-events payload exactly as contracts/event-schemas/kafka-topics.md fixes it, for
 * ORDER_FILLED, ORDER_REJECTED and ORDER_CANCELLED alike. Extracted to a
 * top-level type (was nested in KafkaOrderRepository) so both this service
 * and trade-api-service can each hold their own copy without depending on
 * one another's packages.
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
