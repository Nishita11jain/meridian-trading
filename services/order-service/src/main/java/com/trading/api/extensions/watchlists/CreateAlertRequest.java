package com.trading.api.extensions.watchlists;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Create a price alert: notify when {@code symbol} moves ABOVE or BELOW {@code targetPrice}. */
public record CreateAlertRequest(
        @NotBlank(message = "symbol is required")
        String symbol,

        @NotBlank(message = "direction is required")
        @Pattern(regexp = "^(ABOVE|BELOW)$", message = "direction must be ABOVE or BELOW")
        String direction,

        @NotNull(message = "targetPrice is required")
        @Positive(message = "targetPrice must be greater than zero")
        BigDecimal targetPrice) {
}
