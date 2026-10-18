package com.trading.api.extensions.notifications;

import java.time.Instant;
import java.util.UUID;

/**
 * One notification as the inbox shows it. {@code channel} is the channel the message was routed on,
 * recorded at creation, so the demonstration is a stored fact rather than a claim. {@code readOn} is
 * null until the customer marks the inbox read.
 */
public record NotificationResponse(
        UUID id,
        Long accountId,
        String type,
        String channel,
        String subject,
        String message,
        String deliveryState,
        Instant readOn,
        Instant createdOn,
        // What it is about: the order ("ORD-..." as the blotter shows it), the alert, and the
        // instrument. Null when the notification predates references or is of another kind.
        String orderId,
        Long alertId,
        String symbol
) {
}
