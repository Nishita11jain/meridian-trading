package com.trading.api.extensions.notifications;

/**
 * One notification opened in full: the notification itself, and the order or the alert it is about.
 * order and alert are null when the notification has no reference (it predates references, or is of
 * another kind), or when the referenced record can no longer be read.
 */
public record NotificationDetailResponse(
        NotificationResponse notification,
        OrderDetails order,
        AlertDetails alert
) {
}
