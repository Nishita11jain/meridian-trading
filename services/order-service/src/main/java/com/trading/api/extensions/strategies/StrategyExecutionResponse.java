package com.trading.api.extensions.strategies;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StrategyExecutionResponse(
        Instant triggeredAt,
        BigDecimal triggeringPrice,
        String action,
        String outcome,
        String detail,
        UUID orderId
) {
    static StrategyExecutionResponse from(StrategyExecutionRow row) {
        return new StrategyExecutionResponse(
                row.getTriggeredAt(), row.getTriggeringPrice(), row.getAction(),
                row.getOutcome(), row.getDetail(), row.getOrderId());
    }
}
