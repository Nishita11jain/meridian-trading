package com.trading.api.extensions.strategies;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crossover and Bollinger evaluators over constructed tick series: a clean crossing in each
 * direction only triggers the strategy configured for that side, a strategy on the other side stays
 * silent, and too little history yields no trigger.
 */
class StrategyEvaluatorTest {

    private static StrategyRow crossover(String side, int fast, int slow) {
        StrategyRow row = new StrategyRow();
        row.setStrategyType("CROSSOVER");
        row.setSide(side);
        row.setFastWindow(fast);
        row.setSlowWindow(slow);
        return row;
    }

    private static StrategyRow bollinger(String side, int period, String multiplier) {
        StrategyRow row = new StrategyRow();
        row.setStrategyType("BOLLINGER");
        row.setSide(side);
        row.setBandPeriod(period);
        row.setBandMultiplier(new BigDecimal(multiplier));
        return row;
    }

    /** flatPrice repeated (slow) times, then one last tick that moves the price. */
    private static List<BigDecimal> flatThenJump(double flatPrice, double lastPrice, int flatCount) {
        List<BigDecimal> prices = new ArrayList<>();
        for (int i = 0; i < flatCount; i++) {
            prices.add(BigDecimal.valueOf(flatPrice));
        }
        prices.add(BigDecimal.valueOf(lastPrice));
        return prices;
    }

    @Test
    void aCrossoverUpTriggersOnlyTheBuyStrategy() {
        List<BigDecimal> prices = flatThenJump(100, 300, 10);

        assertTrue(StrategyEvaluator.evaluate(crossover("BUY", 3, 10), prices).isPresent());
        assertEquals(Optional.empty(), StrategyEvaluator.evaluate(crossover("SELL", 3, 10), prices));
    }

    @Test
    void aCrossoverDownTriggersOnlyTheSellStrategy() {
        List<BigDecimal> prices = flatThenJump(200, 50, 10);

        assertTrue(StrategyEvaluator.evaluate(crossover("SELL", 3, 10), prices).isPresent());
        assertEquals(Optional.empty(), StrategyEvaluator.evaluate(crossover("BUY", 3, 10), prices));
    }

    @Test
    void notEnoughHistoryYieldsNoTrigger() {
        List<BigDecimal> prices = flatThenJump(100, 300, 10).subList(0, 5);

        assertEquals(Optional.empty(), StrategyEvaluator.evaluate(crossover("BUY", 3, 10), prices));
    }

    @Test
    void aFlatSeriesNeverTriggers() {
        List<BigDecimal> prices = flatThenJump(150, 150, 10);

        assertEquals(Optional.empty(), StrategyEvaluator.evaluate(crossover("BUY", 3, 10), prices));
        assertEquals(Optional.empty(), StrategyEvaluator.evaluate(crossover("SELL", 3, 10), prices));
    }

    /** Oscillates narrowly around flatPrice (real variance, unlike a perfectly flat series), then one sharp move. */
    private static List<BigDecimal> wobbleThenJump(double flatPrice, double lastPrice, int wobbleCount) {
        List<BigDecimal> prices = new ArrayList<>();
        for (int i = 0; i < wobbleCount; i++) {
            prices.add(BigDecimal.valueOf(flatPrice + (i % 2 == 0 ? 1 : -1)));
        }
        prices.add(BigDecimal.valueOf(lastPrice));
        return prices;
    }

    @Test
    void aPriceCrossingBelowTheLowerBandTriggersOnlyTheBuyStrategy() {
        // A narrow wobble around 100 establishes a real (small) standard deviation, then one sharp
        // drop crosses below the lower band.
        List<BigDecimal> prices = wobbleThenJump(100, 50, 20);

        assertTrue(StrategyEvaluator.evaluate(bollinger("BUY", 20, "2"), prices).isPresent());
        assertEquals(Optional.empty(), StrategyEvaluator.evaluate(bollinger("SELL", 20, "2"), prices));
    }

    @Test
    void aPriceCrossingAboveTheUpperBandTriggersOnlyTheSellStrategy() {
        List<BigDecimal> prices = wobbleThenJump(100, 150, 20);

        assertTrue(StrategyEvaluator.evaluate(bollinger("SELL", 20, "2"), prices).isPresent());
        assertEquals(Optional.empty(), StrategyEvaluator.evaluate(bollinger("BUY", 20, "2"), prices));
    }
}
