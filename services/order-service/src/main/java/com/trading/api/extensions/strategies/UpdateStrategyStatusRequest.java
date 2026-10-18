package com.trading.api.extensions.strategies;

import jakarta.validation.constraints.NotBlank;

/** status must be ACTIVE or DISABLED. Re-enabling clears any prior disabled reason and failure count. */
public record UpdateStrategyStatusRequest(@NotBlank String status) {
}
