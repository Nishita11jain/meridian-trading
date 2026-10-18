package com.trading.api.extensions.notifications;

import com.trading.api.extensions.ProcessedEventsMapper;
import com.trading.api.extensions.preferences.CustomerChannelResolver;
import com.trading.api.service.OrderService;
import com.trading.domain.exception.AccountNotActiveException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Records notifications and serves the inbox. It resolves each customer's channel through the
 * preferences module ({@link CustomerChannelResolver}) rather than holding a channel of its own, so
 * the message goes out on the channel the customer configured.
 *
 * <p>No external email, SMS or push is sent: the resolved channel and a delivery state are recorded,
 * and the in-app inbox is the delivery surface. Real outbound delivery would add a credential and an
 * external failure mode behind the same table; that is a later adapter, not this module's job now.
 *
 * <p>Access control: the inbox routes compare the account in the path against the token's account and
 * refuse a mismatch with ACC-403. The consumer and alert-delivery paths run with no user token and so
 * do not go through those checks; they act on the account named in the event, never a path value.
 */
@Service
public class NotificationsService implements NotificationDelivery {

    private static final Logger log = LoggerFactory.getLogger(NotificationsService.class);

    /** Consumer name for this module's claim in the shared idempotency ledger. Not shared with any other consumer. */
    public static final String CONSUMER_NAME = "notification-service";

    private final NotificationsMapper notificationsMapper;
    private final CustomerChannelResolver channelResolver;
    private final ProcessedEventsMapper processedEvents;
    private final OrderService orderService;
    private final AlertLookup alertLookup;

    public NotificationsService(NotificationsMapper notificationsMapper, CustomerChannelResolver channelResolver,
                                ProcessedEventsMapper processedEvents, OrderService orderService,
                                AlertLookup alertLookup) {
        this.notificationsMapper = notificationsMapper;
        this.channelResolver = channelResolver;
        this.processedEvents = processedEvents;
        this.orderService = orderService;
        this.alertLookup = alertLookup;
    }

    /**
     * Claims a trade-event and records its notification in one transaction. Returns false when the
     * event was already handled (an at-least-once redelivery), so the consumer can acknowledge it
     * without notifying the customer twice. Claiming and creating commit together: if the insert fails
     * the claim rolls back too, so the event is retried rather than lost.
     */
    @Transactional
    public boolean ingestTradeEvent(UUID eventId, long accountId, String type, String subject, String message,
                                    NotificationReference reference) {
        if (processedEvents.claim(CONSUMER_NAME, eventId) == 0) {
            return false;
        }
        createNotification(accountId, eventId, type, subject, message, reference);
        return true;
    }

    /**
     * Records a notification for an account, on the channel its preferences resolve to. Idempotent on
     * the source event id. Used by the trade-events consumer and by the alert-delivery seam.
     */
    public void createNotification(long accountId, UUID sourceEventId, String type, String subject, String message,
                                   NotificationReference reference) {
        NotificationReference ref = reference == null ? NotificationReference.NONE : reference;
        CustomerChannelResolver.NotificationRoute route = channelResolver.resolveChannel(accountId);
        String deliveryState = initialDeliveryState(route);
        
        int created = notificationsMapper.insertIfNew(
                UUID.randomUUID(), accountId, sourceEventId, type, route.channel(), subject, message, deliveryState,
                ref.orderId(), ref.alertId(), ref.symbol());
        if (created == 1) {
            // The routing decision is real and recorded. The contact detail is never logged.
            log.info("Notification recorded: account={}, type={}, channel={}, deliveryState={}", 
                    accountId, type, route.channel(), deliveryState);
        }
    }

    private static String initialDeliveryState(CustomerChannelResolver.NotificationRoute route) {
        String channel = route.channel() == null ? "" : route.channel().trim().toUpperCase();
        return switch (channel) {
            // In-app is available synchronously once the row is persisted.
            case "IN_APP" -> DeliveryState.SENT.getValue();
            // These channels are routed through an asynchronous adapter; for now, this module records
            // the routing decision and queue state only.
            case "EMAIL", "SMS" -> isBlank(route.contact())
                    ? DeliveryState.FAILED.getValue()
                    : DeliveryState.QUEUED.getValue();
            case "PUSH" -> DeliveryState.QUEUED.getValue();
            default -> DeliveryState.FAILED.getValue();
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    public void deliver(long accountId, UUID sourceEventId, String type, String subject, String message,
                        NotificationReference reference) {
        createNotification(accountId, sourceEventId, type, subject, message, reference);
    }

    /**
     * One notification in full, with the order or alert it is about, each read through the module that
     * owns it. Opening is read-only: marking it read is the separate call the inbox makes for an unread
     * one, so a GET never changes state. A reference that can no longer be read is shown as absent.
     */
    public NotificationDetailResponse detail(Long accountId, Long tokenAccountId, UUID id) {
        requireOwnership(accountId, tokenAccountId);
        NotificationRow row = requireNotification(accountId, id);
        OrderDetails order = row.getOrderId() == null ? null
                : orderService.findOwnOrder(row.getOrderId(), accountId).map(OrderDetails::from).orElse(null);
        AlertDetails alert = row.getAlertId() == null ? null
                : alertLookup.findAlert(accountId, row.getAlertId()).orElse(null);
        return new NotificationDetailResponse(toResponse(row), order, alert);
    }

    public NotificationListResponse markRead(Long accountId, Long tokenAccountId, UUID id) {
        requireOwnership(accountId, tokenAccountId);
        requireNotification(accountId, id);
        notificationsMapper.markRead(accountId, id);
        return toListResponse(accountId);
    }

    public NotificationListResponse markUnread(Long accountId, Long tokenAccountId, UUID id) {
        requireOwnership(accountId, tokenAccountId);
        requireNotification(accountId, id);
        notificationsMapper.markUnread(accountId, id);
        return toListResponse(accountId);
    }

    private NotificationRow requireNotification(Long accountId, UUID id) {
        NotificationRow row = notificationsMapper.findOne(accountId, id);
        if (row == null) {
            throw new NotificationNotFoundException("notification not found");
        }
        return row;
    }

    public NotificationListResponse list(Long accountId, Long tokenAccountId) {
        requireOwnership(accountId, tokenAccountId);
        return toListResponse(accountId);
    }

    public NotificationListResponse markAllRead(Long accountId, Long tokenAccountId) {
        requireOwnership(accountId, tokenAccountId);
        notificationsMapper.markAllRead(accountId);
        return toListResponse(accountId);
    }

    private NotificationListResponse toListResponse(Long accountId) {
        List<NotificationResponse> notifications = notificationsMapper.findByAccount(accountId).stream()
                .map(NotificationsService::toResponse)
                .toList();
        return new NotificationListResponse(notifications, notificationsMapper.countUnread(accountId));
    }

    private void requireOwnership(Long accountId, Long tokenAccountId) {
        if (accountId == null || !accountId.equals(tokenAccountId)) {
            throw new AccountNotActiveException("account is not active");
        }
    }

    private static NotificationResponse toResponse(NotificationRow row) {
        return new NotificationResponse(row.getId(), row.getAccountId(), row.getType(), row.getChannel(),
                row.getSubject(), row.getMessage(), row.getDeliveryState(), row.getReadOn(), row.getCreatedOn(),
                row.getOrderId() == null ? null : "ORD-" + row.getOrderId(), row.getAlertId(), row.getSymbol());
    }
}
