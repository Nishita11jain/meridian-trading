package com.trading.api.extensions.notifications;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

@Mapper
public interface NotificationsMapper {

    /**
     * Inserts a notification unless one already exists for this source event. Returns 1 when a row was
     * created, 0 when the source event had already produced one -- a second guard, under the unique
     * source_event_id, on top of the consumer's own idempotency claim.
     */
    int insertIfNew(
            @Param("id") UUID id,
            @Param("accountId") Long accountId,
            @Param("sourceEventId") UUID sourceEventId,
            @Param("type") String type,
            @Param("channel") String channel,
            @Param("subject") String subject,
            @Param("message") String message,
            @Param("deliveryState") String deliveryState,
            @Param("orderId") UUID orderId,
            @Param("alertId") Long alertId,
            @Param("symbol") String symbol);

    List<NotificationRow> findByAccount(@Param("accountId") Long accountId);

    int countUnread(@Param("accountId") Long accountId);

    int markAllRead(@Param("accountId") Long accountId);

    /** One notification, only if it belongs to the account; null otherwise. */
    NotificationRow findOne(@Param("accountId") Long accountId, @Param("id") UUID id);

    int markRead(@Param("accountId") Long accountId, @Param("id") UUID id);

    int markUnread(@Param("accountId") Long accountId, @Param("id") UUID id);
}
