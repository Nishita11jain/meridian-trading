package com.trading.api.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * The market an instrument trades on, and that market's trading hours. Pure rules, no I/O: holidays are
 * passed in. The executor keeps an identical copy (com.trading.executor.domain.TradingMarket); the two
 * services share no library, so each carries the rule and the same tests.
 *
 * <ul>
 *   <li>NSE and BSE ({@code .NS}, {@code .BO}): Monday to Friday, 09:15 to 15:30 India time.</li>
 *   <li>US (a plain ticker such as MSFT): Monday to Friday, 09:30 to 16:00 New York time.</li>
 *   <li>FX ({@code FX:}): Sunday 17:00 to Friday 17:00 New York time, continuously.</li>
 *   <li>Crypto ({@code X:}): always open.</li>
 * </ul>
 * Not modelled: early closes, special sessions, and US pre-market and after-hours trading.
 */
public enum TradingMarket {

    NSE("NSE", ZoneId.of("Asia/Kolkata"), LocalTime.of(9, 15), LocalTime.of(15, 30)),
    BSE("BSE", ZoneId.of("Asia/Kolkata"), LocalTime.of(9, 15), LocalTime.of(15, 30)),
    US("US market", ZoneId.of("America/New_York"), LocalTime.of(9, 30), LocalTime.of(16, 0)),
    FX("FX market", ZoneId.of("America/New_York"), LocalTime.of(17, 0), LocalTime.of(17, 0)),
    CRYPTO("Crypto", ZoneId.of("UTC"), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT);

    private final String displayName;
    private final ZoneId zone;
    private final LocalTime opens;
    private final LocalTime closes;

    TradingMarket(String displayName, ZoneId zone, LocalTime opens, LocalTime closes) {
        this.displayName = displayName;
        this.zone = zone;
        this.opens = opens;
        this.closes = closes;
    }

    public String displayName() {
        return displayName;
    }

    public ZoneId zone() {
        return zone;
    }

    /** The market a symbol trades on, from its form: the same forms the order contract accepts. */
    public static TradingMarket forSymbol(String symbol) {
        String s = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("X:")) {
            return CRYPTO;
        }
        if (s.startsWith("FX:")) {
            return FX;
        }
        if (s.endsWith(".NS")) {
            return NSE;
        }
        if (s.endsWith(".BO")) {
            return BSE;
        }
        return US;
    }

    /** Whether the market is trading at {@code now}. {@code holidays} are exchange-local dates. */
    public boolean isOpen(Instant now, Set<LocalDate> holidays) {
        if (this == CRYPTO) {
            return true;
        }
        ZonedDateTime local = now.atZone(zone);
        if (holidays.contains(local.toLocalDate())) {
            return false;
        }
        DayOfWeek day = local.getDayOfWeek();
        LocalTime time = local.toLocalTime();
        if (this == FX) {
            return switch (day) {
                case SATURDAY -> false;
                case SUNDAY -> !time.isBefore(opens);
                case FRIDAY -> time.isBefore(closes);
                default -> true;
            };
        }
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
                && !time.isBefore(opens) && time.isBefore(closes);
    }

    /**
     * When the market next opens after {@code now}, or null for a market that never closes. Looks up to
     * three weeks ahead, which covers any run of weekends and holidays.
     */
    public Instant nextOpen(Instant now, Set<LocalDate> holidays) {
        if (this == CRYPTO) {
            return null;
        }
        ZonedDateTime local = now.atZone(zone);
        for (int days = 0; days <= 21; days++) {
            LocalDate date = local.toLocalDate().plusDays(days);
            Instant opening = sessionStart(date).atZone(zone).toInstant();
            if (opening.isAfter(now) && isOpen(opening, holidays)) {
                return opening;
            }
        }
        return null;
    }

    /**
     * When trading would begin on a date. FX trades round the clock from Sunday evening, so after a
     * weekday FX holiday it resumes at midnight; every other market opens at its session time.
     */
    private java.time.LocalDateTime sessionStart(LocalDate date) {
        if (this == FX && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
            return date.atStartOfDay();
        }
        return date.atTime(opens);
    }
}
