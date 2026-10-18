package com.trading.api.fx;

import com.trading.domain.exception.DomainException;

/**
 * An order in a foreign currency cannot be placed because there is no rate to convert it with (503 MKT-503).
 * Placing it anyway would reserve and settle cash at a guess.
 */
public final class FxRateUnavailableException extends DomainException {
    public FxRateUnavailableException(String message) {
        super("MKT-503", message);
    }
}
