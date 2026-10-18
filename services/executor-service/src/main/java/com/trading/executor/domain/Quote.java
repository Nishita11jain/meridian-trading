package com.trading.executor.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A Fauxnance quote. The fill rule only needs bid and ask; the remaining
 * fields are passed through onto market-data as the contract requires and are
 * null when Fauxnance did not supply them.
 */
public record Quote(
        String symbol,
        BigDecimal price,
        BigDecimal bid,
        BigDecimal ask,
        Instant asOf,
        String currency,
        BigDecimal change,
        BigDecimal changePercent,
        BigDecimal previousClose,
        String marketState,
        boolean stale
) {

    public Quote(String symbol, BigDecimal price, BigDecimal bid, BigDecimal ask, Instant asOf) {
        this(symbol, price, bid, ask, asOf, null, null, null, null, null, false);
    }

    public Quote(String symbol, BigDecimal price, BigDecimal bid, BigDecimal ask) {
        this(symbol, price, bid, ask, null);
    }
}
