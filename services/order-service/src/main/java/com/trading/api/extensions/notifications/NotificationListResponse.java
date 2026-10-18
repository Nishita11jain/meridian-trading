package com.trading.api.extensions.notifications;

import java.util.List;

/**
 * The inbox and its unread count in one response, so the screen and the sidebar badge share a single
 * call rather than two round trips that could disagree.
 */
public record NotificationListResponse(
        List<NotificationResponse> notifications,
        int unreadCount
) {
}
