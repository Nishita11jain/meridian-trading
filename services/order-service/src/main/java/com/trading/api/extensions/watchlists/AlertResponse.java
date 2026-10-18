package com.trading.api.extensions.watchlists;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A price alert and its state. status is ACTIVE until a quote crosses the threshold, then TRIGGERED
 * (with when and at what price), or CANCELLED if the customer cancels it.
 */
public record AlertResponse(
        Long id,
        String symbol,
        String direction,
        BigDecimal targetPrice,
        String status,
        Instant createdOn,
        Instant triggeredOn,
        BigDecimal triggeredPrice) {
}
