package com.trading.api.dto;

import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;

import java.math.BigDecimal;

public record OrderResponse(
        String orderId,
        OrderStatus status,
        String message,
        String symbol,
        OrderSide side,
        int quantity,
        BigDecimal price
) {
}
