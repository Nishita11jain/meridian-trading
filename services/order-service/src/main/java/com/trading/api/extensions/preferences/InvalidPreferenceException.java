package com.trading.api.extensions.preferences;

import com.trading.domain.exception.DomainException;

/**
 * A preference value that is well-formed but not acceptable: a channel outside the allowed set, a
 * theme that is not one of ours, a currency the platform does not trade in, or a contact detail that
 * does not match the chosen channel. Maps to VAL-422 through the shared GlobalExceptionHandler, like
 * every other DomainException, so the envelope and status match the rest of the platform.
 */
public final class InvalidPreferenceException extends DomainException {
    public InvalidPreferenceException(String message) {
        super("VAL-422", message);
    }
}
