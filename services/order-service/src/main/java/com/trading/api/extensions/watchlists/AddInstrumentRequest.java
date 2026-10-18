package com.trading.api.extensions.watchlists;

import jakarta.validation.constraints.NotBlank;

/** Add a tradable instrument to a watchlist by symbol. */
public record AddInstrumentRequest(
        @NotBlank(message = "symbol is required")
        String symbol) {
}
