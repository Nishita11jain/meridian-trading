package com.trading.api.service;

import com.trading.api.exception.MarketClosedException;
import com.trading.api.mapper.MarketHolidaysMapper;
import com.trading.api.mapper.row.MarketHolidayRow;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The trading-hours rule. 2026-10-02 is a Friday; 2026-10-03 and 04 are the weekend. Times are given
 * in UTC: India is UTC+05:30, New York is UTC-04:00 in October (daylight time).
 */
class TradingHoursTest {

    private static final Set<LocalDate> NONE = Set.of();

    private static Instant at(String utc) {
        return Instant.parse(utc);
    }

    // ------------------------------------------------------------------ which market

    @Test
    void theSymbolsFormSaysWhichMarketItTradesOn() {
        assertEquals(TradingMarket.NSE, TradingMarket.forSymbol("TCS.NS"));
        assertEquals(TradingMarket.BSE, TradingMarket.forSymbol("reliance.bo"));
        assertEquals(TradingMarket.US, TradingMarket.forSymbol("MSFT"));
        assertEquals(TradingMarket.FX, TradingMarket.forSymbol("FX:GBPUSD"));
        assertEquals(TradingMarket.CRYPTO, TradingMarket.forSymbol("X:ETH-USD"));
    }

    // ------------------------------------------------------------------ session markets

    @Test
    void nseIsOpenFrom0915To1530IndiaTimeOnWeekdays() {
        TradingMarket nse = TradingMarket.NSE;
        assertFalse(nse.isOpen(at("2026-10-02T03:44:59Z"), NONE), "09:14:59 IST");
        assertTrue(nse.isOpen(at("2026-10-02T03:45:00Z"), NONE), "09:15 IST opens");
        assertTrue(nse.isOpen(at("2026-10-02T09:59:59Z"), NONE), "15:29:59 IST");
        assertFalse(nse.isOpen(at("2026-10-02T10:00:00Z"), NONE), "15:30 IST closes");
        assertFalse(nse.isOpen(at("2026-10-03T05:00:00Z"), NONE), "Saturday");
    }

    @Test
    void aClosedNseOnFridayEveningOpensMondayMorning() {
        Instant monday0915Ist = at("2026-10-05T03:45:00Z");
        assertEquals(monday0915Ist, TradingMarket.NSE.nextOpen(at("2026-10-02T12:00:00Z"), NONE));
    }

    @Test
    void beforeTheOpenItOpensTheSameDay() {
        assertEquals(at("2026-10-02T03:45:00Z"), TradingMarket.NSE.nextOpen(at("2026-10-02T01:00:00Z"), NONE));
    }

    @Test
    void aHolidayIsClosedAndSkippedWhenFindingTheNextOpen() {
        Set<LocalDate> monday = Set.of(LocalDate.parse("2026-10-05"));
        assertFalse(TradingMarket.NSE.isOpen(at("2026-10-05T05:00:00Z"), monday));
        assertEquals(at("2026-10-06T03:45:00Z"), TradingMarket.NSE.nextOpen(at("2026-10-02T12:00:00Z"), monday));
    }

    @Test
    void usIsOpenFrom0930To1600NewYorkTime() {
        TradingMarket us = TradingMarket.US;
        assertFalse(us.isOpen(at("2026-10-02T13:29:00Z"), NONE), "09:29 New York");
        assertTrue(us.isOpen(at("2026-10-02T13:30:00Z"), NONE), "09:30 New York");
        assertFalse(us.isOpen(at("2026-10-02T20:00:00Z"), NONE), "16:00 New York");
    }

    // ------------------------------------------------------------------ FX and crypto

    @Test
    void fxTradesFromSundayEveningToFridayEveningNewYorkTime() {
        TradingMarket fx = TradingMarket.FX;
        assertTrue(fx.isOpen(at("2026-10-01T03:00:00Z"), NONE), "overnight on a weekday");
        assertTrue(fx.isOpen(at("2026-10-02T20:59:00Z"), NONE), "Friday 16:59");
        assertFalse(fx.isOpen(at("2026-10-02T21:00:00Z"), NONE), "Friday 17:00 closes");
        assertFalse(fx.isOpen(at("2026-10-03T12:00:00Z"), NONE), "Saturday");
        assertFalse(fx.isOpen(at("2026-10-04T20:59:00Z"), NONE), "Sunday 16:59");
        assertTrue(fx.isOpen(at("2026-10-04T21:00:00Z"), NONE), "Sunday 17:00 opens");
        assertEquals(at("2026-10-04T21:00:00Z"), fx.nextOpen(at("2026-10-03T12:00:00Z"), NONE));
    }

    @Test
    void afterAWeekdayFxHolidayFxResumesAtMidnight() {
        Set<LocalDate> wednesday = Set.of(LocalDate.parse("2026-10-07"));
        assertFalse(TradingMarket.FX.isOpen(at("2026-10-07T15:00:00Z"), wednesday));
        assertEquals(at("2026-10-08T04:00:00Z"), TradingMarket.FX.nextOpen(at("2026-10-07T15:00:00Z"), wednesday));
    }

    @Test
    void cryptoIsAlwaysOpen() {
        assertTrue(TradingMarket.CRYPTO.isOpen(at("2026-10-03T12:00:00Z"), NONE));
        assertNull(TradingMarket.CRYPTO.nextOpen(at("2026-10-03T12:00:00Z"), NONE));
    }

    // ------------------------------------------------------------------ the component

    private static TradingHours hoursAt(String utc, MarketHolidayRow... holidays) {
        MarketHolidaysMapper mapper = mock(MarketHolidaysMapper.class);
        when(mapper.findBetween(any(), any())).thenReturn(List.of(holidays));
        return new TradingHours(mapper, Clock.fixed(at(utc), ZoneOffset.UTC));
    }

    private static MarketHolidayRow holiday(String market, String date) {
        MarketHolidayRow row = new MarketHolidayRow();
        row.setMarket(market);
        row.setHolidayDate(LocalDate.parse(date));
        return row;
    }

    @Test
    void aClosedMarketIsRefusedWithMkt409SayingWhenItOpens() {
        TradingHours hours = hoursAt("2026-10-02T12:00:00Z"); // Friday 17:30 IST

        MarketClosedException refusal = assertThrows(MarketClosedException.class, () -> hours.requireOpen("TCS.NS"));

        assertEquals("MKT-409", refusal.code());
        assertTrue(refusal.getMessage().contains("TCS.NS trades on the NSE, which is closed now"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("Mon 5 Oct, 09:15 IST"), refusal.getMessage());
    }

    @Test
    void anOpenMarketAndCryptoAreNotRefused() {
        TradingHours hours = hoursAt("2026-10-02T05:00:00Z"); // Friday 10:30 IST, Saturday-free

        assertDoesNotThrow(() -> hours.requireOpen("TCS.NS"));
        assertDoesNotThrow(() -> hours.requireOpen("X:ETH-USD"));
    }

    @Test
    void holidaysFromTheTableCloseThatMarketOnly() {
        TradingHours hours = hoursAt("2026-10-02T05:00:00Z", holiday("NSE", "2026-10-02"));

        assertThrows(MarketClosedException.class, () -> hours.requireOpen("TCS.NS"));
        assertTrue(hours.status("RELIANCE.BO").open(), "a holiday is per market");
    }

    @Test
    void statusesGiveEachSymbolItsMarketStateAndNextOpen() {
        TradingHours hours = hoursAt("2026-10-02T12:00:00Z");

        var statuses = hours.statuses(List.of("TCS.NS", "FX:GBPUSD", "X:ETH-USD"));

        assertFalse(statuses.get("TCS.NS").open());
        assertEquals(at("2026-10-05T03:45:00Z"), statuses.get("TCS.NS").nextOpen());
        assertTrue(statuses.get("FX:GBPUSD").open(), "Friday 08:00 New York");
        assertNull(statuses.get("FX:GBPUSD").nextOpen());
        assertTrue(statuses.get("X:ETH-USD").open());
    }

    @Test
    void anUnknownMarketInTheTableIsIgnored() {
        TradingHours hours = hoursAt("2026-10-02T05:00:00Z", holiday("LSE", "2026-10-02"));

        assertTrue(hours.status("TCS.NS").open());
    }
}
