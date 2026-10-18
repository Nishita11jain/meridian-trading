package com.trading.api.extensions.advice;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * The one methodology this module commits to: an SMA20/SMA50 crossover over daily closes. A fast
 * average rising through the slow one is read as BUY (momentum turning up); falling through it is
 * SELL; anything else -- including not enough history to compute SMA50 at all -- is left as no
 * signal, never a guess dressed up as one.
 *
 * <p>The "cross" is the event, not the ordering: comparing only the latest fast/slow pair would
 * fire on every call once the two have settled either side of each other, not once at the moment
 * they actually crossed. So this compares the latest pair against the previous day's pair.
 */
final class MovingAverageSignalCalculator {

    static final int FAST_WINDOW = 20;
    static final int SLOW_WINDOW = 50;

    /** How many of the most recent days the Analyse-now chart plots, regardless of window size. */
    static final int CHART_WINDOW = 90;

    private MovingAverageSignalCalculator() {
    }

    /**
     * The chart behind a signal: the last {@code CHART_WINDOW} closes, each paired with that day's
     * SMA20/SMA50 wherever enough preceding history exists to compute them. Unlike {@link #compute},
     * this never returns empty -- even a handful of closes is worth plotting as a bare price line,
     * with the averages appearing once the window fills in.
     */
    static List<ChartPoint> series(List<FauxnanceCandleClient.Close> closes) {
        int from = Math.max(0, closes.size() - CHART_WINDOW);
        List<ChartPoint> points = new java.util.ArrayList<>();
        for (int i = from; i < closes.size(); i++) {
            int endExclusive = i + 1;
            BigDecimal fast = endExclusive >= FAST_WINDOW ? sma(closes, endExclusive, FAST_WINDOW) : null;
            BigDecimal slow = endExclusive >= SLOW_WINDOW ? sma(closes, endExclusive, SLOW_WINDOW) : null;
            FauxnanceCandleClient.Close close = closes.get(i);
            points.add(new ChartPoint(close.date(), close.close(), fast, slow));
        }
        return points;
    }

    /** {@code closes} must be ordered oldest to newest. Returns empty when there is not enough history. */
    static java.util.Optional<Signal> compute(List<FauxnanceCandleClient.Close> closes) {
        if (closes.size() < SLOW_WINDOW + 1) {
            return java.util.Optional.empty();
        }

        BigDecimal fastNow = sma(closes, closes.size(), FAST_WINDOW);
        BigDecimal slowNow = sma(closes, closes.size(), SLOW_WINDOW);
        BigDecimal fastPrev = sma(closes, closes.size() - 1, FAST_WINDOW);
        BigDecimal slowPrev = sma(closes, closes.size() - 1, SLOW_WINDOW);

        String direction;
        if (fastPrev.compareTo(slowPrev) <= 0 && fastNow.compareTo(slowNow) > 0) {
            direction = "BUY";
        } else if (fastPrev.compareTo(slowPrev) >= 0 && fastNow.compareTo(slowNow) < 0) {
            direction = "SELL";
        } else {
            direction = "HOLD";
        }

        BigDecimal strength = slowNow.signum() == 0
                ? BigDecimal.ZERO
                : fastNow.subtract(slowNow).abs().divide(slowNow, new MathContext(4, RoundingMode.HALF_UP));
        // Strength is a distance, not a probability: cap it at 1 so the UI's strength bar never overflows.
        if (strength.compareTo(BigDecimal.ONE) > 0) {
            strength = BigDecimal.ONE;
        }

        java.time.LocalDate asOf = closes.get(closes.size() - 1).date();
        String rationale = switch (direction) {
            case "BUY" -> String.format(
                    "SMA%d (%s) crossed above SMA%d (%s) on %s.",
                    FAST_WINDOW, fastNow, SLOW_WINDOW, slowNow, asOf);
            case "SELL" -> String.format(
                    "SMA%d (%s) crossed below SMA%d (%s) on %s.",
                    FAST_WINDOW, fastNow, SLOW_WINDOW, slowNow, asOf);
            default -> String.format(
                    "SMA%d (%s) and SMA%d (%s) show no crossover as of %s.",
                    FAST_WINDOW, fastNow, SLOW_WINDOW, slowNow, asOf);
        };

        return java.util.Optional.of(new Signal(direction, strength, rationale, summary(direction)));
    }

    /**
     * The one-line, jargon-free reading of a direction -- what SMA20/SMA50 means to someone who
     * does not know what a moving average is. Shown alongside the technical rationale, never in
     * place of it: the numbers stay the source of truth, this is just the plain-English headline.
     * A pure function of {@code direction} alone, so a cached row (which stores direction but not
     * this text) can still get one, via {@link AdviceService}: nothing here needs to be persisted.
     */
    static String summary(String direction) {
        return switch (direction) {
            case "BUY" -> "Short-term momentum has turned positive: recent prices are rising faster than the longer-term trend.";
            case "SELL" -> "Short-term momentum has turned negative: recent prices are falling faster than the longer-term trend.";
            default -> "No clear trend right now: short-term and longer-term prices are moving roughly together.";
        };
    }

    /** The mean of the last {@code window} closes ending at (exclusive) index {@code endExclusive}. */
    private static BigDecimal sma(List<FauxnanceCandleClient.Close> closes, int endExclusive, int window) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = endExclusive - window; i < endExclusive; i++) {
            sum = sum.add(closes.get(i).close());
        }
        return sum.divide(BigDecimal.valueOf(window), new MathContext(8, RoundingMode.HALF_UP));
    }

    record Signal(String direction, BigDecimal strength, String rationale, String summary) {
    }
}
