package com.trading.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderTest {

    @Test
    void fillMarksOrderAsTerminalAndStoresExecutedPrice() {
        Order order = newOrder();

        order.fill(Money.of("101.25"));

        assertEquals(OrderStatus.FILLED, order.getStatus());
        assertEquals(Money.of("101.25"), order.getExecutedPrice());
        assertTrue(order.isTerminal());
    }

    @Test
    void rejectMarksOrderAsRejected() {
        Order order = newOrder();

        order.reject();

        assertEquals(OrderStatus.REJECTED, order.getStatus());
        assertTrue(order.isTerminal());
    }

    @Test
    void terminalOrderRefusesFurtherStateChanges() {
        Order order = newOrder();
        order.cancel();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> order.fill(Money.of("99.00")));

        assertTrue(exception.getMessage().contains("already terminal"));
    }

    private static Order newOrder() {
        return new Order(UUID.randomUUID(), 1L, "INFY.NS", OrderSide.BUY, 10, Money.of("100.00"),
                "idem-key-001", Instant.parse("2026-09-08T10:00:00Z"));
    }
}