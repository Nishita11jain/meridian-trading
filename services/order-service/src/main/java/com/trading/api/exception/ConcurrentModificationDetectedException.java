package com.trading.api.exception;

import com.trading.domain.exception.DomainException;

/**
 * Raised when an optimistic-lock write affects zero rows -- someone else
 * already wrote the account or position row first. This is a
 * persistence-layer concern, not a Sprint 5 business rule, which is why it
 * lives here rather than in domain-engine.
 *
 * <p>Open question worth raising with the instructor: contracts/api-schemas/trade-api.yaml's
 * ErrorResponse.errorCode enum has no code for "someone else already
 * modified this concurrently, retry." ORD-409 is the closest semantic fit
 * (a conflict, same family as the duplicate-idempotency-key case) and is
 * used here as a considered choice, not an assumption -- Story 5, which
 * owns the full transactional/locking design, is the right place to
 * confirm or replace this.
 */
public final class ConcurrentModificationDetectedException extends DomainException {

    public ConcurrentModificationDetectedException(String message) {
        super("ORD-409", message);
    }
}
