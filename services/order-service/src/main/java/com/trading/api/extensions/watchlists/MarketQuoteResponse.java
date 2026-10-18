package com.trading.api.extensions.watchlists;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The latest quote the platform holds for a symbol, from the market_price read model the market-data
 * consumer maintains. {@code stale} and {@code quoteAsOf} let the UI show how old a price is rather
 * than passing an old one off as live.
 */
public record MarketQuoteResponse(
        String symbol,
        BigDecimal lastPrice,
        BigDecimal bid,
        BigDecimal ask,
        String currency,
        BigDecimal change,
        BigDecimal changePercent,
        BigDecimal previousClose,
        String marketState,
        boolean stale,
        Instant quoteAsOf,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice
) {
}
