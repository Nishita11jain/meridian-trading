package com.trading.executor.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The executor's copy of the trading-hours rule, with the same cases as the Trade API's TradingHoursTest.
 * 2026-10-02 is a Friday; 2026-10-03 and 04 are the weekend. Times are given
 * in UTC: India is UTC+05:30, New York is UTC-04:00 in October (daylight time).
 */
class TradingMarketTest {

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
}
