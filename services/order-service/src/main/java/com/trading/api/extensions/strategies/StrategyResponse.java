package com.trading.api.extensions.strategies;

import java.math.BigDecimal;
import java.time.Instant;

public record StrategyResponse(
        Long id,
        String symbol,
        String side,
        String strategyType,
        Integer fastWindow,
        Integer slowWindow,
        Integer bandPeriod,
        BigDecimal bandMultiplier,
        Integer orderQuantity,
        BigDecimal maxSpend,
        Integer maxPositionSize,
        Integer maxConsecutiveFailures,
        Integer consecutiveFailures,
        String status,
        String disabledReason,
        Instant createdOn
) {
    static StrategyResponse from(StrategyRow row) {
        return new StrategyResponse(
                row.getId(), row.getSymbol(), row.getSide(), row.getStrategyType(),
                row.getFastWindow(), row.getSlowWindow(), row.getBandPeriod(), row.getBandMultiplier(),
                row.getOrderQuantity(), row.getMaxSpend(), row.getMaxPositionSize(),
                row.getMaxConsecutiveFailures(), row.getConsecutiveFailures(),
                row.getStatus(), row.getDisabledReason(), row.getCreatedOn());
    }
}
