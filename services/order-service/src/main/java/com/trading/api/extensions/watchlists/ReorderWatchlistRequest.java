package com.trading.api.extensions.watchlists;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The watchlist's instruments in the order the customer wants them. Symbols not on the list are
 * ignored; instruments on the list but not named keep their place after the named ones.
 */
public record ReorderWatchlistRequest(
        @NotNull(message = "symbols is required")
        @Size(max = 250, message = "too many symbols")
        List<String> symbols) {
}
