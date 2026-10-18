package com.trading.api.extensions.watchlists;

/**
 * One instrument on a watchlist, with the latest quote beside it (null when none has been polled) and
 * the colour flag the customer put on it: RED, AMBER or GREEN, or null for none.
 */
public record WatchlistItemResponse(String symbol, MarketQuoteResponse quote, String flag) {
}
