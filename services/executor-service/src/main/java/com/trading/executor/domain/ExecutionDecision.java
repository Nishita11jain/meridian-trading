package com.trading.executor.domain;

import java.math.BigDecimal;

public record ExecutionDecision(
        boolean fill,
        BigDecimal executedPrice,
        String reason
) {

    public static ExecutionDecision fill(
            BigDecimal price) {

        return new ExecutionDecision(
                true,
                price,
                "filled at quote"
        );
    }

    public static ExecutionDecision reject(
            String reason) {

        return new ExecutionDecision(
                false,
                null,
                reason
        );
    }
}