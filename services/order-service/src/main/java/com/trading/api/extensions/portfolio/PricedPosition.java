package com.trading.api.extensions.portfolio;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One holding, priced. marketValue/unrealisedPnl are null when no price is available; stale says the
 * price is old or missing, and priceAsOf when it was observed. Cost basis is always known.
 */
public record PricedPosition(
        Long accountId,
        String symbol,
        int quantity,
        BigDecimal averageCost,
        BigDecimal costBasis,
        BigDecimal lastPrice,
        BigDecimal marketValue,
        BigDecimal unrealisedPnl,
        BigDecimal unrealisedPnlPercent,
        String currency,
        Instant priceAsOf,
        boolean stale
) {
}
