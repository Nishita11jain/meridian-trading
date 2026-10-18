package com.trading.executor.client;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FauxnanceQuotaTrackerTest {

    @Test
    void tryReserveReturnsTrueUntilQuotaIsExhaustedThenFalse() {
        FauxnanceQuotaTracker tracker = new FauxnanceQuotaTracker(
                Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC)
        );

        for (int i = 0; i < 2000; i++) {
            assertTrue(tracker.tryReserve(1));
        }

        assertFalse(tracker.tryReserve(1));
        assertEquals(0, tracker.remainingToday());
    }

    @Test
    void quotaResetsAfterUtcDayRollsOver() {
        FauxnanceQuotaTracker tracker = new FauxnanceQuotaTracker(
                Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC)
        );

        assertTrue(tracker.tryReserve(1));
        assertEquals(1999, tracker.remainingToday());

        tracker.setResetDateForTest(LocalDate.parse("2026-09-16"));

        assertTrue(tracker.tryReserve(1));
        assertEquals(1999, tracker.remainingToday());
    }
}
