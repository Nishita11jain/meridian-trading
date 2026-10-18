package com.trading.api.extensions.notifications;

import java.math.BigDecimal;
import java.time.Instant;

/** The price alert a notification is about, as the detail view shows it. */
public record AlertDetails(
        Long alertId,
        String symbol,
        String direction,
        BigDecimal targetPrice,
        String status,
        Instant triggeredOn,
        BigDecimal triggeredPrice
) {
}
