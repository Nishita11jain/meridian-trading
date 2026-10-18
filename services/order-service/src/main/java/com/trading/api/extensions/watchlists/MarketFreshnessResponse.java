package com.trading.api.extensions.watchlists;

import java.time.Instant;

/**
 * When the platform last stored prices, and when the next batch is due, so a page can refresh in step
 * with the feed (once just after each batch) instead of on a fixed timer. {@code lastUpdatedOn} is when
 * the platform stored them (market_price.updated_on), not the exchange time of the quote, which stays
 * at the last trade all night for a closed market. Both are null before any price has been stored.
 */
public record MarketFreshnessResponse(
        Instant lastUpdatedOn,
        Instant nextUpdateAt,
        int pollIntervalSeconds
) {
}
