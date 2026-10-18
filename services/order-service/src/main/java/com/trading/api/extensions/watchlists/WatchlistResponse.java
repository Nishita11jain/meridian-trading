package com.trading.api.extensions.watchlists;

import java.time.Instant;
import java.util.List;

/** A watchlist and its instruments, each with its current price. */
public record WatchlistResponse(Long id, String name, Instant createdOn, List<WatchlistItemResponse> items) {
}
