package com.trading.api.extensions.notifications;

import java.util.UUID;

/**
 * The seam the watchlists module hands a triggered alert to, in-process rather than over HTTP. This
 * is the only part of the notifications module another module may call. Publishing it as an HTTP route
 * would let anyone holding a customer token send that customer a message, which is the access-control
 * finding the brief warns about, so it stays an interface.
 *
 * <p>The caller supplies the source event id it is delivering for; delivery is idempotent on it, so a
 * redelivered market-data event that re-triggers the same alert does not notify the customer twice.
 */
public interface NotificationDelivery {

    /**
     * Records a notification for the account on the channel its preferences resolve to. No external
     * message is sent; the resolved channel and a delivery state are recorded, which is the inbox the
     * customer reads. Idempotent: a second call with the same {@code sourceEventId} creates nothing.
     * {@code reference} says what the notification is about (the alert and its instrument, for watchlists),
     * so the customer can open it and see the details.
     */
    void deliver(long accountId, UUID sourceEventId, String type, String subject, String message,
                 NotificationReference reference);
}
