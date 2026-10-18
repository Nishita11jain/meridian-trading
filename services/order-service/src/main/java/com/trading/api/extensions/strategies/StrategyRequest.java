package com.trading.api.extensions.strategies;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * A new strategy. Exactly one of (fastWindow, slowWindow) or (bandPeriod, bandMultiplier) is
 * required, depending on strategyType; {@link StrategiesService} enforces which, since that
 * cross-field rule does not express cleanly as a single annotation here.
 */
public record StrategyRequest(
        @NotBlank String symbol,
        @NotBlank String side,
        @NotBlank String strategyType,
        Integer fastWindow,
        Integer slowWindow,
        Integer bandPeriod,
        BigDecimal bandMultiplier,
        @NotNull @Positive Integer orderQuantity,
        @NotNull @Positive BigDecimal maxSpend,
        @NotNull @Positive Integer maxPositionSize,
        @NotNull @Positive Integer maxConsecutiveFailures
) {
}
