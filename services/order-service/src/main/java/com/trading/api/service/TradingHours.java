package com.trading.api.service;

import com.trading.api.exception.MarketClosedException;
import com.trading.api.mapper.MarketHolidaysMapper;
import com.trading.api.mapper.row.MarketHolidayRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Whether an instrument's market is open now, and when it next opens: the one place the Trade API
 * answers that. Order placement refuses a closed market with it (MKT-409), and the instrument list
 * carries its answer so the UI shows Open/Closed without a copy of the hours. The hours are in
 * {@link TradingMarket}; holidays come from the market_holidays table.
 */
@Component
public class TradingHours {

    /** Holidays are read for this window around today: enough for the longest nextOpen search. */
    private static final int HOLIDAY_WINDOW_DAYS = 23;

    private static final DateTimeFormatter OPENS = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm z", Locale.ENGLISH);

    private final MarketHolidaysMapper holidaysMapper;
    private final Clock clock;

    @Autowired
    public TradingHours(MarketHolidaysMapper holidaysMapper) {
        this(holidaysMapper, Clock.systemUTC());
    }

    public TradingHours(MarketHolidaysMapper holidaysMapper, Clock clock) {
        this.holidaysMapper = holidaysMapper;
        this.clock = clock;
    }

    /** A market's state at one moment. {@code nextOpen} is null while open, and for a market that never closes. */
    public record TradingStatus(TradingMarket market, boolean open, Instant nextOpen) {
    }

    public TradingStatus status(String symbol) {
        return statuses(List.of(symbol)).get(symbol);
    }

    /** Statuses for many symbols, reading the holiday calendar once. Keyed by the symbols as given. */
    public Map<String, TradingStatus> statuses(Collection<String> symbols) {
        Instant now = clock.instant();
        Map<TradingMarket, Set<LocalDate>> holidays = holidaysAround(now);
        Map<String, TradingStatus> result = new LinkedHashMap<>();
        for (String symbol : symbols) {
            TradingMarket market = TradingMarket.forSymbol(symbol);
            Set<LocalDate> days = holidays.getOrDefault(market, Set.of());
            boolean open = market.isOpen(now, days);
            result.put(symbol, new TradingStatus(market, open, open ? null : market.nextOpen(now, days)));
        }
        return result;
    }

    /** Refuses with MKT-409 when the symbol's market is closed, saying when it opens. */
    public void requireOpen(String symbol) {
        TradingStatus status = status(symbol);
        if (status.open()) {
            return;
        }
        String when = status.nextOpen() == null ? ""
                : " It opens " + OPENS.format(status.nextOpen().atZone(status.market().zone())) + ".";
        throw new MarketClosedException(symbol + " trades on the " + status.market().displayName()
                + ", which is closed now." + when);
    }

    private Map<TradingMarket, Set<LocalDate>> holidaysAround(Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        List<MarketHolidayRow> rows = holidaysMapper.findBetween(today.minusDays(1), today.plusDays(HOLIDAY_WINDOW_DAYS));
        Map<TradingMarket, Set<LocalDate>> byMarket = new HashMap<>();
        for (MarketHolidayRow row : rows == null ? List.<MarketHolidayRow>of() : rows) {
            try {
                byMarket.computeIfAbsent(TradingMarket.valueOf(row.getMarket()), m -> new HashSet<>()).add(row.getHolidayDate());
            } catch (IllegalArgumentException unknownMarket) {
                // A market the rule does not know is ignored rather than failing every request.
            }
        }
        return byMarket;
    }
}
