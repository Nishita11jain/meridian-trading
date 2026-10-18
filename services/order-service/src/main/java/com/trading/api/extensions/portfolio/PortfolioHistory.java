package com.trading.api.extensions.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One currency's portfolio over time: at each point, what the holdings were worth and what had been
 * invested in them (their cost basis). Amounts are never converted between currencies, so a mixed
 * portfolio has one of these per currency.
 *
 * <p>A holding is valued at the latest price known at that moment: a price the platform stored, or else
 * the price of the account's own latest trade in it (a real market price at that moment). A point valued
 * with any trade price is {@code estimated}; {@code marketPricesSince} is the first point valued from
 * stored prices only (null if none is), and {@code valueSince} the first point with a value at all.
 */
public record PortfolioHistory(
        String currency,
        String range,
        Instant valueSince,
        Instant marketPricesSince,
        List<Point> points
) {

    public record Point(Instant at, BigDecimal value, BigDecimal invested, boolean estimated) {
    }
}
