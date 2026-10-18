package com.trading.executor.domain;

import com.trading.api.event.OrderPlacedEvent;
import com.trading.domain.OrderSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FillRuleTest {

    @Test
    void buyFillsWhenLimitIsAboveAsk() {

        OrderPlacedEvent.Payload order = order(
                OrderSide.BUY,
                "100.00"
        );

        Quote quote = new Quote(
                "INFY.NS",
                new BigDecimal("94.995"),
                new BigDecimal("94.99"),
                new BigDecimal("95.00")
        );

        ExecutionDecision result = FillRule.decide(order, quote);

        assertTrue(result.fill());
        assertEquals(
                new BigDecimal("95.00"),
                result.executedPrice()
        );
    }

    @Test
    void buyFillsWhenLimitEqualsAsk() {

        OrderPlacedEvent.Payload order = order(
                OrderSide.BUY,
                "100.00"
        );

        Quote quote = new Quote(
                "INFY.NS",
                new BigDecimal("99.98"),
                new BigDecimal("99.95"),
                new BigDecimal("100.00")
        );

        ExecutionDecision result = FillRule.decide(order, quote);

        assertTrue(result.fill());
        assertEquals(
                new BigDecimal("100.00"),
                result.executedPrice()
        );
    }

    @Test
    void buyRejectsWhenLimitIsBelowAsk() {

        OrderPlacedEvent.Payload order = order(
                OrderSide.BUY,
                "100.00"
        );

        Quote quote = new Quote(
                "INFY.NS",
                new BigDecimal("105.00"),
                new BigDecimal("104.99"),
                new BigDecimal("105.01")
        );

        ExecutionDecision result = FillRule.decide(order, quote);

        assertFalse(result.fill());
        assertEquals(
                "PRICE_NOT_MET",
                result.reason()
        );
    }

    @Test
    void sellFillsWhenLimitIsBelowBid() {

        OrderPlacedEvent.Payload order = order(
                OrderSide.SELL,
                "100.00"
        );

        Quote quote = new Quote(
                "INFY.NS",
                new BigDecimal("105.005"),
                new BigDecimal("105.01"),
                new BigDecimal("105.02")
        );

        ExecutionDecision result = FillRule.decide(order, quote);

        assertTrue(result.fill());
        assertEquals(
                new BigDecimal("105.01"),
                result.executedPrice()
        );
    }

    @Test
    void sellFillsWhenLimitEqualsBid() {

        OrderPlacedEvent.Payload order = order(
                OrderSide.SELL,
                "100.00"
        );

        Quote quote = new Quote(
                "INFY.NS",
                new BigDecimal("100.02"),
                new BigDecimal("100.00"),
                new BigDecimal("100.05")
        );

        ExecutionDecision result = FillRule.decide(order, quote);

        assertTrue(result.fill());
        assertEquals(
                new BigDecimal("100.00"),
                result.executedPrice()
        );
    }

    @Test
    void sellRejectsWhenLimitIsAboveBid() {

        OrderPlacedEvent.Payload order = order(
                OrderSide.SELL,
                "100.00"
        );

        Quote quote = new Quote(
                "INFY.NS",
                new BigDecimal("95.00"),
                new BigDecimal("94.98"),
                new BigDecimal("95.02")
        );

        ExecutionDecision result = FillRule.decide(order, quote);

        assertFalse(result.fill());
        assertEquals(
                "PRICE_NOT_MET",
                result.reason()
        );
    }

    @Test
    void missingQuoteRejectsOrder() {

        OrderPlacedEvent.Payload order = order(
                OrderSide.BUY,
                "100.00"
        );

        ExecutionDecision result = FillRule.decide(order, null);

        assertFalse(result.fill());
        assertEquals(
                "PRICING_UNAVAILABLE",
                result.reason()
        );
    }

    @Test
    void quoteMissingBidOrAskRejectsOrder() {

        OrderPlacedEvent.Payload order = order(
                OrderSide.BUY,
                "100.00"
        );

        Quote quote = new Quote("INFY.NS", new BigDecimal("99.00"), null, null);

        ExecutionDecision result = FillRule.decide(order, quote);

        assertFalse(result.fill());
        assertEquals(
                "PRICING_UNAVAILABLE",
                result.reason()
        );
    }

    private static OrderPlacedEvent.Payload order(
            OrderSide side,
            String limitPrice) {

        return new OrderPlacedEvent.Payload(
                UUID.randomUUID(),
                1L,
                "INFY.NS",
                side,
                10,
                new BigDecimal(limitPrice),
                "idem-key"
        );
    }
}