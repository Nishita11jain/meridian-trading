package com.trading.api.extensions.portfolio;

import java.math.BigDecimal;
import java.time.Instant;

/** The portfolio in one base currency, as portfolio-api.yaml defines it. */
public record PortfolioSummary(
        Long accountId,
        String baseCurrency,
        BigDecimal cashBalance,
        BigDecimal marketValue,
        BigDecimal costBasis,
        BigDecimal unrealisedPnl,
        BigDecimal unrealisedPnlPercent,
        BigDecimal realisedPnl,
        BigDecimal totalValue,
        int positionCount,
        boolean partial,
        Instant asOf
) {
}
