package com.trading.api.service;

import com.trading.api.mapper.OrderMapper;
import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;
import com.trading.domain.PlaceOrderRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RejectedOrderRecorderTest {

    @Test
    void recordsRejectedOrderWithExpectedArguments() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        RejectedOrderRecorder recorder = new RejectedOrderRecorder(orderMapper);

        PlaceOrderRequest request = new PlaceOrderRequest(
                42L,
                "AAPL",
                OrderSide.BUY,
                10,
                new BigDecimal("123.45"),
                "idem-key-1234"
        );

        UUID id = recorder.recordRejection(request, RejectedOrderRecorder.INSUFFICIENT_FUNDS);

        // The row carries the reason and the decision time, like one the executor rejected.
        verify(orderMapper).insertRejected(
                eq(id),
                eq(42L),
                eq("AAPL"),
                eq(OrderSide.BUY),
                eq(10L),
                eq(new BigDecimal("123.45")),
                eq("idem-key-1234"),
                eq("INSUFFICIENT_FUNDS"),
                any(Instant.class)
        );
    }
}
