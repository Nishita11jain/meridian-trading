package com.trading.api.extensions.watchlists;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Rename a watchlist; the name stays unique within the account. */
public record RenameWatchlistRequest(
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name is too long")
        String name) {
}
