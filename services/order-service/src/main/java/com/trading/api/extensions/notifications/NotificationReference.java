package com.trading.api.extensions.notifications;

import java.util.UUID;

/**
 * What a notification is about: the order (for a trade-events notification) or the price alert (for a
 * triggered alert), and the instrument. Part of the delivery seam, so a caller says what it is
 * notifying about when it hands a message over. Any field may be null.
 */
public record NotificationReference(UUID orderId, String symbol, Long alertId) {

    public static final NotificationReference NONE = new NotificationReference(null, null, null);

    public static NotificationReference order(UUID orderId, String symbol) {
        return new NotificationReference(orderId, symbol, null);
    }

    public static NotificationReference alert(long alertId, String symbol) {
        return new NotificationReference(null, symbol, alertId);
    }
}
