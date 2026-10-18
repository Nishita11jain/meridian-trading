package com.trading.api.dto;

import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderHistoryEntry(
        String orderId,
        Long accountId,
        String symbol,
        OrderSide side,
        int quantity,
        BigDecimal price,
        BigDecimal executedPrice,
        OrderStatus status,
        String idempotencyKey,
        Instant createdOn,
        // Why a REJECTED order was rejected (a reason code such as INSUFFICIENT_FUNDS or
        // PRICE_NOT_MET); null for every other status. Optional in contracts/trade-api.yaml.
        String rejectionReason
) {
}
