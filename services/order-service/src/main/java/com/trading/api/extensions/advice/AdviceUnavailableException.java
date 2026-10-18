package com.trading.api.extensions.advice;

import com.trading.domain.exception.DomainException;

/**
 * Fauxnance could not be reached and no cached candles exist yet for this symbol, so no signal can
 * be computed at all. Maps to 503 ADV-503. A refresh that fails with an older cached signal still on
 * hand is not this: that case serves the stale signal, marked stale, rather than failing the call --
 * availability over strict freshness, the same choice Customer Preferences made for its own resolver.
 */
public final class AdviceUnavailableException extends DomainException {
    public AdviceUnavailableException(String message) {
        super("ADV-503", message);
    }
}
