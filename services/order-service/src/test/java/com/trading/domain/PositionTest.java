package com.trading.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionTest {

    @Test
    void openNewStartsAtZeroWithZeroAverageCost() {
        Position position = Position.openNew(1L, "INFY.NS");

        assertEquals(0, position.getQuantity());
        assertEquals(Money.ZERO, position.getAverageCost());
    }

    @Test
    void applyBuyRecomputesWeightedAverageCost() {
        Position position = new Position(1L, "INFY.NS", 10, Money.of("100.00"), 0);

        position.applyBuy(10, Money.of("120.00"));

        assertEquals(20, position.getQuantity());
        assertEquals(Money.of("110.00"), position.getAverageCost());
    }

    @Test
    void applySellRequiresSufficientHoldings() {
        Position position = new Position(1L, "INFY.NS", 10, Money.of("100.00"), 0);
        position.applySell(4);

        assertEquals(6, position.getQuantity());
        assertTrue(position.hasSufficientHoldings(6));
        assertThrows(IllegalStateException.class, () -> position.applySell(7));
    }
}