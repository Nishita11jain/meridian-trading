package com.trading.executor.service;

import com.trading.api.mapper.MarketHolidaysMapper;
import com.trading.executor.domain.TradingMarket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Whether an order's market is open at settlement. The Trade API refuses a closed market at placement;
 * this catches an order that reaches the executor after the close (placed at 15:29:59, settled at
 * 15:30:01), which is then rejected MARKET_CLOSED before any quote is fetched.
 */
@Component
public class MarketHours {

    private final MarketHolidaysMapper holidaysMapper;
    private final Clock clock;

    @Autowired
    public MarketHours(MarketHolidaysMapper holidaysMapper) {
        this(holidaysMapper, Clock.systemUTC());
    }

    public MarketHours(MarketHolidaysMapper holidaysMapper, Clock clock) {
        this.holidaysMapper = holidaysMapper;
        this.clock = clock;
    }

    public boolean isOpen(String symbol) {
        TradingMarket market = TradingMarket.forSymbol(symbol);
        if (market == TradingMarket.CRYPTO) {
            return true;
        }
        Instant now = clock.instant();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        List<LocalDate> holidays = holidaysMapper.findBetween(market.name(), today.minusDays(1), today.plusDays(1));
        Set<LocalDate> days = holidays == null ? Set.of() : new HashSet<>(holidays);
        return market.isOpen(now, days);
    }
}
