package com.trading.api.extensions.advice;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The SMA20/SMA50 crossover over a few constructed candle series: a clean upward cross, a clean
 * downward cross, a flat series with no cross, and too little history to compute SMA50 at all.
 */
class MovingAverageSignalCalculatorTest {

    private static List<FauxnanceCandleClient.Close> flatThenJump(double flatPrice, double lastPrice) {
        List<FauxnanceCandleClient.Close> closes = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 1, 1);
        // 50 flat candles (indices 0-49), so SMA20/SMA50 both equal flatPrice up to and including
        // the previous day -- no crossover has happened yet.
        for (int i = 0; i < 50; i++) {
            closes.add(new FauxnanceCandleClient.Close(date.plusDays(i), BigDecimal.valueOf(flatPrice)));
        }
        // One more candle (index 50) that moves the price. The 20-window average reacts more than
        // the 50-window average, which is exactly what a crossover is.
        closes.add(new FauxnanceCandleClient.Close(date.plusDays(50), BigDecimal.valueOf(lastPrice)));
        return closes;
    }

    @Test
    void notEnoughHistoryYieldsNoSignal() {
        List<FauxnanceCandleClient.Close> closes = flatThenJump(100, 100).subList(0, 10);

        Optional<MovingAverageSignalCalculator.Signal> signal = MovingAverageSignalCalculator.compute(closes);

        assertTrue(signal.isEmpty());
    }

    @Test
    void aSharpRiseCrossesUpAndSignalsBuy() {
        List<FauxnanceCandleClient.Close> closes = flatThenJump(100, 300);

        Optional<MovingAverageSignalCalculator.Signal> signal = MovingAverageSignalCalculator.compute(closes);

        assertTrue(signal.isPresent());
        assertEquals("BUY", signal.get().direction());
        assertTrue(signal.get().strength().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(signal.get().rationale().contains("crossed above"));
        // The plain-English summary is a separate field from the technical rationale, not a
        // replacement for it -- both are shown together.
        assertTrue(signal.get().summary().toLowerCase().contains("positive"));
    }

    @Test
    void aSharpFallCrossesDownAndSignalsSell() {
        List<FauxnanceCandleClient.Close> closes = flatThenJump(200, 50);

        Optional<MovingAverageSignalCalculator.Signal> signal = MovingAverageSignalCalculator.compute(closes);

        assertTrue(signal.isPresent());
        assertEquals("SELL", signal.get().direction());
        assertTrue(signal.get().rationale().contains("crossed below"));
        assertTrue(signal.get().summary().toLowerCase().contains("negative"));
    }

    @Test
    void aFlatSeriesWithNoCrossoverSignalsHold() {
        List<FauxnanceCandleClient.Close> closes = flatThenJump(150, 150);

        Optional<MovingAverageSignalCalculator.Signal> signal = MovingAverageSignalCalculator.compute(closes);

        assertTrue(signal.isPresent());
        assertEquals("HOLD", signal.get().direction());
    }

    @Test
    void strengthIsNeverAboveOne() {
        // An extreme, almost discontinuous jump: the raw distance would exceed 1 without the cap.
        List<FauxnanceCandleClient.Close> closes = flatThenJump(10, 100_000);

        Optional<MovingAverageSignalCalculator.Signal> signal = MovingAverageSignalCalculator.compute(closes);

        assertTrue(signal.isPresent());
        assertFalse(signal.get().strength().compareTo(BigDecimal.ONE) > 0);
    }

    @Test
    void seriesPlotsOneChartPointPerClose() {
        List<FauxnanceCandleClient.Close> closes = flatThenJump(100, 300);

        List<ChartPoint> chart = MovingAverageSignalCalculator.series(closes);

        assertEquals(closes.size(), chart.size());
        assertEquals(closes.get(0).date(), chart.get(0).date());
        assertEquals(closes.get(closes.size() - 1).close(), chart.get(chart.size() - 1).close());
    }

    @Test
    void seriesLeavesTheAveragesNullBeforeEnoughHistoryExists() {
        List<FauxnanceCandleClient.Close> closes = flatThenJump(100, 300);

        List<ChartPoint> chart = MovingAverageSignalCalculator.series(closes);

        // Day 0 has no preceding history at all: neither average can be computed yet.
        assertNull(chart.get(0).sma20());
        assertNull(chart.get(0).sma50());
        // 51 closes total; sma50 needs 50 preceding closes, so only the last two days (indices
        // 49 and 50) have it -- everything earlier in the window is still null.
        ChartPoint last = chart.get(chart.size() - 1);
        assertEquals(2, countNonNullAverages(chart));
        assertTrue(last.sma20() != null && last.sma50() != null);
    }

    @Test
    void seriesCapsAtTheChartWindowOnLongerHistory() {
        List<FauxnanceCandleClient.Close> closes = new java.util.ArrayList<>();
        LocalDate date = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < 200; i++) {
            closes.add(new FauxnanceCandleClient.Close(date.plusDays(i), BigDecimal.valueOf(100 + i)));
        }

        List<ChartPoint> chart = MovingAverageSignalCalculator.series(closes);

        assertEquals(MovingAverageSignalCalculator.CHART_WINDOW, chart.size());
        assertEquals(closes.get(closes.size() - 1).date(), chart.get(chart.size() - 1).date());
    }

    private static int countNonNullAverages(List<ChartPoint> chart) {
        return (int) chart.stream().filter(p -> p.sma50() != null).count();
    }
}
