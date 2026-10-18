package com.trading.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record BalanceResponse(
        Long accountId,
        BigDecimal cashBalance,
        String currency,
        Instant asOf
) {
}
