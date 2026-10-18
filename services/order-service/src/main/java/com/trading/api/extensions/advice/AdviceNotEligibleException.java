package com.trading.api.extensions.advice;

import com.trading.domain.exception.DomainException;

/**
 * The symbol asked about is neither held nor watched by this account. A watchlist entry is not a
 * position and a holding is not a watch, but advice is only ever offered for one of the two --
 * never for an arbitrary tradable instrument the customer has shown no interest in.
 */
public final class AdviceNotEligibleException extends DomainException {
    public AdviceNotEligibleException(String message) {
        super("ADV-404", message);
    }
}
