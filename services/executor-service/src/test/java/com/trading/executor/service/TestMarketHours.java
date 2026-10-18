package com.trading.executor.service;

import com.trading.api.mapper.MarketHolidaysMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;

/** Market hours for tests: a fixed moment, with no holidays. */
final class TestMarketHours {

    /** Wednesday 30 September 2026, 10:30 India time: NSE, US-closed, FX open. */
    static final Instant NSE_OPEN = Instant.parse("2026-09-30T05:00:00Z");
    /** Friday 2 October 2026, 17:30 India time: NSE closed. */
    static final Instant NSE_CLOSED = Instant.parse("2026-10-02T12:00:00Z");

    private TestMarketHours() {
    }

    static MarketHours at(Instant now) {
        MarketHolidaysMapper holidays = mock(MarketHolidaysMapper.class);
        // Lenient: a test that is rejected before the market check never asks for holidays.
        lenient().when(holidays.findBetween(any(), any(), any())).thenReturn(List.of());
        return new MarketHours(holidays, Clock.fixed(now, ZoneOffset.UTC));
    }

    static MarketHours nseOpen() {
        return at(NSE_OPEN);
    }
}
