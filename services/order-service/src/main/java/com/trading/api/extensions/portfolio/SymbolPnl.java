package com.trading.api.extensions.portfolio;

import java.math.BigDecimal;

/** One instrument's profit and loss in the base currency. */
public record SymbolPnl(
        String symbol,
        BigDecimal realisedPnl,
        BigDecimal unrealisedPnl,
        BigDecimal totalPnl
) {
}
