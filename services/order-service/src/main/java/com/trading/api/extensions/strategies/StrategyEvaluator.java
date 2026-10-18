package com.trading.api.extensions.strategies;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Whether a strategy's condition just fired, given its recent quote history. Pure and
 * side-effect-free: no Kafka, no database, no order placement -- {@link StrategyExecutionService}
 * does all of that around this.
 *
 * <p>Both methods look for a <em>crossing event between the last two ticks</em>, never "is the
 * condition currently true": comparing only the latest state would fire on every quote once the
 * condition has settled, not once at the moment it actually changed. A strategy is single-sided, so
 * a crossing that doesn't match the strategy's configured {@code side} is not a trigger at all --
 * a BUY strategy never reacts to a downward cross, even though the same market-data tick would be a
 * real trigger for a SELL strategy watching the same symbol.
 */
final class StrategyEvaluator {

    private StrategyEvaluator() {
    }

    static Optional<Trigger> evaluate(StrategyRow strategy, List<BigDecimal> recentPrices) {
        return switch (strategy.getStrategyType()) {
            case "CROSSOVER" -> evaluateCrossover(strategy, recentPrices);
            case "BOLLINGER" -> evaluateBollinger(strategy, recentPrices);
            default -> Optional.empty();
        };
    }

    private static Optional<Trigger> evaluateCrossover(StrategyRow strategy, List<BigDecimal> prices) {
        int fast = strategy.getFastWindow();
        int slow = strategy.getSlowWindow();
        if (prices.size() < slow + 1) {
            return Optional.empty();
        }

        BigDecimal fastNow = sma(prices, prices.size(), fast);
        BigDecimal slowNow = sma(prices, prices.size(), slow);
        BigDecimal fastPrev = sma(prices, prices.size() - 1, fast);
        BigDecimal slowPrev = sma(prices, prices.size() - 1, slow);

        boolean crossedUp = fastPrev.compareTo(slowPrev) <= 0 && fastNow.compareTo(slowNow) > 0;
        boolean crossedDown = fastPrev.compareTo(slowPrev) >= 0 && fastNow.compareTo(slowNow) < 0;

        if ("BUY".equals(strategy.getSide()) && crossedUp) {
            return Optional.of(new Trigger(prices.get(prices.size() - 1),
                    String.format("SMA%d (%s) crossed above SMA%d (%s).", fast, fastNow, slow, slowNow)));
        }
        if ("SELL".equals(strategy.getSide()) && crossedDown) {
            return Optional.of(new Trigger(prices.get(prices.size() - 1),
                    String.format("SMA%d (%s) crossed below SMA%d (%s).", fast, fastNow, slow, slowNow)));
        }
        return Optional.empty();
    }

    private static Optional<Trigger> evaluateBollinger(StrategyRow strategy, List<BigDecimal> prices) {
        int period = strategy.getBandPeriod();
        BigDecimal multiplier = strategy.getBandMultiplier();
        if (prices.size() < period + 1) {
            return Optional.empty();
        }

        BigDecimal currPrice = prices.get(prices.size() - 1);
        BigDecimal prevPrice = prices.get(prices.size() - 2);

        BigDecimal[] nowBand = band(prices, prices.size(), period, multiplier);
        BigDecimal[] prevBand = band(prices, prices.size() - 1, period, multiplier);
        BigDecimal nowLower = nowBand[0];
        BigDecimal nowUpper = nowBand[1];
        BigDecimal prevLower = prevBand[0];
        BigDecimal prevUpper = prevBand[1];

        boolean crossedBelowLower = prevPrice.compareTo(prevLower) > 0 && currPrice.compareTo(nowLower) <= 0;
        boolean crossedAboveUpper = prevPrice.compareTo(prevUpper) < 0 && currPrice.compareTo(nowUpper) >= 0;

        if ("BUY".equals(strategy.getSide()) && crossedBelowLower) {
            return Optional.of(new Trigger(currPrice,
                    String.format("Price (%s) crossed below the lower band (%s).", currPrice, nowLower)));
        }
        if ("SELL".equals(strategy.getSide()) && crossedAboveUpper) {
            return Optional.of(new Trigger(currPrice,
                    String.format("Price (%s) crossed above the upper band (%s).", currPrice, nowUpper)));
        }
        return Optional.empty();
    }

    /** {@code [lowerBand, upperBand]} for the {@code period} prices ending at (exclusive) {@code endExclusive}. */
    private static BigDecimal[] band(List<BigDecimal> prices, int endExclusive, int period, BigDecimal multiplier) {
        BigDecimal mean = sma(prices, endExclusive, period);
        BigDecimal variance = BigDecimal.ZERO;
        for (int i = endExclusive - period; i < endExclusive; i++) {
            BigDecimal diff = prices.get(i).subtract(mean);
            variance = variance.add(diff.multiply(diff));
        }
        variance = variance.divide(BigDecimal.valueOf(period), new MathContext(10, RoundingMode.HALF_UP));
        BigDecimal stdDev = variance.sqrt(new MathContext(10, RoundingMode.HALF_UP));
        BigDecimal spread = stdDev.multiply(multiplier);
        return new BigDecimal[]{mean.subtract(spread), mean.add(spread)};
    }

    private static BigDecimal sma(List<BigDecimal> prices, int endExclusive, int window) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = endExclusive - window; i < endExclusive; i++) {
            sum = sum.add(prices.get(i));
        }
        return sum.divide(BigDecimal.valueOf(window), new MathContext(8, RoundingMode.HALF_UP));
    }

    record Trigger(BigDecimal triggerPrice, String rationale) {
    }
}
