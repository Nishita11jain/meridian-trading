package com.trading.api.extensions.watchlists;

import java.util.List;

/**
 * A ready-made list: every tradable instrument of one market (e.g. all NSE stocks). Creating a watchlist
 * from it gives the customer an ordinary list of their own, which the poller then prices like any other.
 */
public record WatchlistPresetResponse(String key, String name, List<String> symbols) {
}
