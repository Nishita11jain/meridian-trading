package com.trading.api.extensions.watchlists;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create a watchlist with a name unique within the account. */
public record CreateWatchlistRequest(
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name is too long")
        String name) {
}
