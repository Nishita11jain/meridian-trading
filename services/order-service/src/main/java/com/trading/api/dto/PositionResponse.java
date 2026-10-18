package com.trading.api.dto;

import java.math.BigDecimal;

public record PositionResponse(
        Long accountId,
        String symbol,
        int quantity,
        BigDecimal averageCost
) {
}
