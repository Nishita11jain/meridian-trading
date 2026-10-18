package com.trading.api.extensions.watchlists;

import java.time.Instant;

/**
 * A tradable instrument, for the symbol pickers and the market board, with its market's state now:
 * {@code tradingStatus} OPEN or CLOSED, and {@code nextOpen} when a closed market opens (null while
 * open, and for crypto, which never closes). The UI shows these rather than keeping its own hours.
 */
public record InstrumentResponse(
        String symbol,
        String name,
        String assetClass,
        String currency,
        String market,
        String tradingStatus,
        Instant nextOpen
) {
}
