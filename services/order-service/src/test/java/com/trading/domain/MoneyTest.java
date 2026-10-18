package com.trading.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    void factoryRoundsToTwoDecimalPlaces() {
        assertEquals(new BigDecimal("123.46"), Money.of(new BigDecimal("123.456")).asBigDecimal());
    }

    @Test
    void arithmeticProducesExpectedValues() {
        Money base = Money.of("10.00");

        assertEquals(Money.of("12.50"), base.add(Money.of("2.50")));
        assertEquals(Money.of("7.50"), base.subtract(Money.of("2.50")));
        assertEquals(Money.of("30.00"), base.multiply(3));
    }

    @Test
    void comparisonHelpersReflectAmountSignAndOrdering() {
        assertTrue(Money.of("10.00").isGreaterThanOrEqualTo(Money.of("9.99")));
        assertTrue(Money.of("-0.01").isNegative());
        assertFalse(Money.ZERO.isNegative());
    }
}