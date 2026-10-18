package com.trading.api.extensions.notifications;

import com.trading.domain.exception.DomainException;

/** A notification that does not exist, or is not the caller's. 404 either way, so ownership is not disclosed. */
public final class NotificationNotFoundException extends DomainException {
    public NotificationNotFoundException(String message) {
        super("NTF-404", message);
    }
}
