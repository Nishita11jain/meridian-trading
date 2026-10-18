package com.trading.api.extensions.portfolio;

import com.trading.domain.exception.DomainException;

/**
 * No price could be found for any held instrument, so a portfolio cannot be valued at all. Maps to
 * 503 MKT-503. A partial outage (some symbols priced, some not) is not this: that returns 200 with the
 * unpriced positions marked stale and the summary marked partial, per the contract.
 */
public final class PricingUnavailableException extends DomainException {
    public PricingUnavailableException(String message) {
        super("MKT-503", message);
    }
}
