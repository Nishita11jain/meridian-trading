package com.trading.executor.domain;

import com.trading.api.event.OrderPlacedEvent;
import com.trading.domain.Money;
import com.trading.domain.OrderSide;

import java.math.BigDecimal;

public final class FillRule {

    private FillRule() {
    }

    public static ExecutionDecision decide(OrderPlacedEvent.Payload order, Quote quote) {

        if (quote == null || quote.bid() == null || quote.ask() == null) {
            return ExecutionDecision.reject(RejectionReason.PRICING_UNAVAILABLE);
        }

        BigDecimal limit = order.price();

        if (order.side() == OrderSide.BUY) {
            BigDecimal ask = Money.of(quote.ask()).asBigDecimal();
            if (limit.compareTo(ask) >= 0) {
                return ExecutionDecision.fill(ask);
            }
            return ExecutionDecision.reject(RejectionReason.PRICE_NOT_MET);
        }

        BigDecimal bid = Money.of(quote.bid()).asBigDecimal();
        if (limit.compareTo(bid) <= 0) {
            return ExecutionDecision.fill(bid);
        }
        return ExecutionDecision.reject(RejectionReason.PRICE_NOT_MET);
    }
}