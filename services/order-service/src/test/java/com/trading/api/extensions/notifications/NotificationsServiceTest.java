package com.trading.api.extensions.notifications;

import com.trading.api.extensions.ProcessedEventsMapper;
import com.trading.api.dto.OrderHistoryEntry;
import com.trading.api.extensions.preferences.CustomerChannelResolver;
import com.trading.api.service.OrderService;
import com.trading.domain.OrderSide;
import com.trading.domain.OrderStatus;
import com.trading.domain.exception.AccountNotActiveException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The notifications service over stand-in mappers: idempotent ingest, channel resolution through the
 * preferences seam, and the cross-account refusal on the inbox routes.
 */
class NotificationsServiceTest {

    private static final long ACCOUNT = 4L;
    private static final UUID EVENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private NotificationsMapper notificationsMapper;
    private CustomerChannelResolver resolver;
    private ProcessedEventsMapper processedEvents;
    private OrderService orderService;
    private AlertLookup alertLookup;
    private NotificationsService service;

    @BeforeEach
    void setUp() {
        notificationsMapper = mock(NotificationsMapper.class);
        resolver = mock(CustomerChannelResolver.class);
        processedEvents = mock(ProcessedEventsMapper.class);
        orderService = mock(OrderService.class);
        alertLookup = mock(AlertLookup.class);
        when(resolver.resolveChannel(anyLong())).thenReturn(new CustomerChannelResolver.NotificationRoute("EMAIL", "a@b.com"));
        when(notificationsMapper.insertIfNew(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        service = new NotificationsService(notificationsMapper, resolver, processedEvents, orderService, alertLookup);
    }

    @Test
    void ingestClaimsTheEventRecordsItOnTheResolvedChannelAndReportsCreated() {
        when(processedEvents.claim("notification-service", EVENT)).thenReturn(1);

        boolean created = service.ingestTradeEvent(EVENT, ACCOUNT, "ORDER_FILLED", "Order filled: TCS.NS", "body",
                NotificationReference.order(ORDER, "TCS.NS"));

        assertTrue(created);
        verify(resolver).resolveChannel(ACCOUNT);
        verify(notificationsMapper).insertIfNew(any(), eq(ACCOUNT), eq(EVENT), eq("ORDER_FILLED"),
                eq("EMAIL"), eq("Order filled: TCS.NS"), eq("body"), eq("QUEUED"), eq(ORDER), eq(null), eq("TCS.NS"));
    }

    @Test
    void aDuplicateEventIsNotRecordedAgain() {
        when(processedEvents.claim("notification-service", EVENT)).thenReturn(0);

        boolean created = service.ingestTradeEvent(EVENT, ACCOUNT, "ORDER_FILLED", "s", "b", NotificationReference.NONE);

        assertFalse(created);
        verify(notificationsMapper, never()).insertIfNew(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theDeliverySeamRecordsOnTheResolvedChannelToo() {
        service.deliver(ACCOUNT, EVENT, "PRICE_ALERT", "Alert", "crossed", NotificationReference.alert(7L, "TCS.NS"));

        verify(notificationsMapper).insertIfNew(any(), eq(ACCOUNT), eq(EVENT), eq("PRICE_ALERT"),
                eq("EMAIL"), eq("Alert"), eq("crossed"), eq("QUEUED"), eq(null), eq(7L), eq("TCS.NS"));
    }

        @Test
        void channelIsResolvedOnEverySendSoAPreferenceChangeChangesTheRecordedChannel() {
        UUID second = UUID.fromString("55555555-5555-5555-5555-555555555555");
        when(resolver.resolveChannel(ACCOUNT))
            .thenReturn(new CustomerChannelResolver.NotificationRoute("EMAIL", "a@b.com"))
            .thenReturn(new CustomerChannelResolver.NotificationRoute("SMS", "+919999999999"));

        service.deliver(ACCOUNT, EVENT, "ORDER_FILLED", "s1", "b1", NotificationReference.NONE);
        service.deliver(ACCOUNT, second, "ORDER_REJECTED", "s2", "b2", NotificationReference.NONE);

        verify(resolver, times(2)).resolveChannel(ACCOUNT);
        var calls = inOrder(notificationsMapper);
        calls.verify(notificationsMapper).insertIfNew(any(), eq(ACCOUNT), eq(EVENT), eq("ORDER_FILLED"),
            eq("EMAIL"), eq("s1"), eq("b1"), eq("QUEUED"), eq(null), eq(null), eq(null));
        calls.verify(notificationsMapper).insertIfNew(any(), eq(ACCOUNT), eq(second), eq("ORDER_REJECTED"),
            eq("SMS"), eq("s2"), eq("b2"), eq("QUEUED"), eq(null), eq(null), eq(null));
        }

    @Test
    void listReturnsTheInboxWithItsUnreadCount() {
        when(notificationsMapper.findByAccount(ACCOUNT)).thenReturn(List.of(row("Order filled: TCS.NS", null)));
        when(notificationsMapper.countUnread(ACCOUNT)).thenReturn(3);

        NotificationListResponse response = service.list(ACCOUNT, ACCOUNT);

        assertEquals(1, response.notifications().size());
        assertEquals(3, response.unreadCount());
    }

    @Test
    void listForAnotherAccountIsRefusedAcc403() {
        AccountNotActiveException refusal = assertThrows(AccountNotActiveException.class,
                () -> service.list(5L, ACCOUNT));

        assertEquals("ACC-403", refusal.code());
        verify(notificationsMapper, never()).findByAccount(any());
    }

    @Test
    void markAllReadForAnotherAccountIsRefusedAndChangesNothing() {
        assertThrows(AccountNotActiveException.class, () -> service.markAllRead(5L, ACCOUNT));

        verify(notificationsMapper, never()).markAllRead(any());
    }

    @Test
    void markAllReadClearsUnreadForTheOwnAccount() {
        when(notificationsMapper.findByAccount(ACCOUNT)).thenReturn(List.of());
        when(notificationsMapper.countUnread(ACCOUNT)).thenReturn(0);

        NotificationListResponse response = service.markAllRead(ACCOUNT, ACCOUNT);

        verify(notificationsMapper).markAllRead(ACCOUNT);
        assertEquals(0, response.unreadCount());
    }

    private static NotificationRow row(String subject, Instant readOn) {
        NotificationRow row = new NotificationRow();
        row.setId(UUID.randomUUID());
        row.setAccountId(ACCOUNT);
        row.setType("ORDER_FILLED");
        row.setChannel("EMAIL");
        row.setSubject(subject);
        row.setMessage("body");
        row.setDeliveryState("SENT");
        row.setReadOn(readOn);
        row.setCreatedOn(Instant.parse("2026-10-01T00:00:00Z"));
        return row;
    }

    // ------------------------------------------------------------ one notification, opened

    @Test
    void openingAnOrderNotificationShowsTheOrderReadThroughTheOrderService() {
        NotificationRow row = row("Order rejected: TCS.NS", null);
        row.setOrderId(ORDER);
        row.setSymbol("TCS.NS");
        when(notificationsMapper.findOne(ACCOUNT, ID)).thenReturn(row);
        when(orderService.findOwnOrder(ORDER, ACCOUNT)).thenReturn(Optional.of(new OrderHistoryEntry(
                "ORD-" + ORDER, ACCOUNT, "TCS.NS", OrderSide.BUY, 1000, new BigDecimal("56678.00"), null,
                OrderStatus.REJECTED, "k", Instant.parse("2026-10-01T09:00:00Z"), "INSUFFICIENT_FUNDS")));

        NotificationDetailResponse detail = service.detail(ACCOUNT, ACCOUNT, ID);

        assertEquals("ORD-" + ORDER, detail.notification().orderId());
        assertEquals("TCS.NS", detail.notification().symbol());
        assertEquals("INSUFFICIENT_FUNDS", detail.order().rejectionReason());
        assertEquals("BUY", detail.order().side());
        assertEquals(1000, detail.order().quantity());
        assertNull(detail.alert());
        // Opening is read-only: marking read is a separate call.
        verify(notificationsMapper, never()).markRead(any(), any());
    }

    @Test
    void openingAnAlertNotificationShowsTheAlertReadThroughTheLookup() {
        NotificationRow row = row("Price alert: TCS.NS", null);
        row.setAlertId(7L);
        when(notificationsMapper.findOne(ACCOUNT, ID)).thenReturn(row);
        AlertDetails alert = new AlertDetails(7L, "TCS.NS", "ABOVE", new BigDecimal("3500"), "TRIGGERED",
                Instant.parse("2026-10-01T09:05:00Z"), new BigDecimal("3510.25"));
        when(alertLookup.findAlert(ACCOUNT, 7L)).thenReturn(Optional.of(alert));

        NotificationDetailResponse detail = service.detail(ACCOUNT, ACCOUNT, ID);

        assertEquals(alert, detail.alert());
        assertNull(detail.order());
        verify(orderService, never()).findOwnOrder(any(), anyLong());
    }

    @Test
    void aNotificationWithoutAReferenceOpensWithItsOwnTextOnly() {
        when(notificationsMapper.findOne(ACCOUNT, ID)).thenReturn(row("Older notification", null));

        NotificationDetailResponse detail = service.detail(ACCOUNT, ACCOUNT, ID);

        assertEquals("Older notification", detail.notification().subject());
        assertNull(detail.notification().orderId());
        assertNull(detail.order());
        assertNull(detail.alert());
    }

    @Test
    void aReferenceThatCanNoLongerBeReadIsShownAsAbsentNotAnError() {
        NotificationRow row = row("Order filled: TCS.NS", null);
        row.setOrderId(ORDER);
        when(notificationsMapper.findOne(ACCOUNT, ID)).thenReturn(row);
        when(orderService.findOwnOrder(ORDER, ACCOUNT)).thenReturn(Optional.empty());

        assertNull(service.detail(ACCOUNT, ACCOUNT, ID).order());
    }

    @Test
    void anUnknownOrOtherAccountsNotificationIsNtf404() {
        when(notificationsMapper.findOne(ACCOUNT, ID)).thenReturn(null);

        NotificationNotFoundException missing = assertThrows(NotificationNotFoundException.class,
                () -> service.detail(ACCOUNT, ACCOUNT, ID));
        assertEquals("NTF-404", missing.code());
        assertThrows(NotificationNotFoundException.class, () -> service.markRead(ACCOUNT, ACCOUNT, ID));
        assertThrows(NotificationNotFoundException.class, () -> service.markUnread(ACCOUNT, ACCOUNT, ID));
        verify(notificationsMapper, never()).markRead(any(), any());
        verify(notificationsMapper, never()).markUnread(any(), any());
    }

    @Test
    void openingAnotherAccountsInboxIsRefusedBeforeAnyLookup() {
        assertThrows(AccountNotActiveException.class, () -> service.detail(5L, ACCOUNT, ID));
        assertThrows(AccountNotActiveException.class, () -> service.markRead(5L, ACCOUNT, ID));
        assertThrows(AccountNotActiveException.class, () -> service.markUnread(5L, ACCOUNT, ID));
        verify(notificationsMapper, never()).findOne(any(), any());
    }

    @Test
    void markingOneReadOrUnreadChangesOnlyThatOneAndReturnsTheInbox() {
        when(notificationsMapper.findOne(ACCOUNT, ID)).thenReturn(row("s", null));
        when(notificationsMapper.findByAccount(ACCOUNT)).thenReturn(List.of());
        when(notificationsMapper.countUnread(ACCOUNT)).thenReturn(2, 3);

        assertEquals(2, service.markRead(ACCOUNT, ACCOUNT, ID).unreadCount());
        verify(notificationsMapper).markRead(ACCOUNT, ID);
        assertEquals(3, service.markUnread(ACCOUNT, ACCOUNT, ID).unreadCount());
        verify(notificationsMapper).markUnread(ACCOUNT, ID);
        verify(notificationsMapper, never()).markAllRead(any());
    }
}
