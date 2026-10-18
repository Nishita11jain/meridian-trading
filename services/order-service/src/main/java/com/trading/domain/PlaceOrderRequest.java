package com.trading.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PlaceOrderRequest(

        @NotNull
        @Min(1)
        Long accountId,

        @NotBlank
        @Size(max = 20)
        String symbol,

        @NotNull
        OrderSide side,

        @NotNull
        @Positive
        Integer quantity,

        @NotNull
        @DecimalMin(value = "0.01")
        @Digits(integer = 17, fraction = 2)
        BigDecimal price,

        @NotBlank
        @Size(min = 8, max = 100)
        String idempotencyKey
) {
}