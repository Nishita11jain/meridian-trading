package com.trading.api.extensions.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The consumer's job: parse a trade-events message into a readable notification, and acknowledge only
 * when the work is done or the message is beyond saving. The service is mocked; this is about parsing,
 * the subject/body wording, and the acknowledge-or-retry decision.
 */
class TradeEventsConsumerTest {

    private static final UUID EVENT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private NotificationsService notifications;
    private Acknowledgment ack;
    private TradeEventsConsumer consumer;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationsService.class);
        ack = mock(Acknowledgment.class);
        when(notifications.ingestTradeEvent(any(), anyLong(), any(), any(), any(), any())).thenReturn(true);
        consumer = new TradeEventsConsumer(new ObjectMapper(), notifications);
    }

    private static String event(String eventType, String status, String reason, String executedPrice) {
        return "{"
                + "\"eventId\":\"" + EVENT + "\","
                + "\"eventType\":\"" + eventType + "\","
                + "\"eventTime\":\"2026-10-01T09:00:00Z\","
                + "\"source\":\"trade-api\",\"schemaVersion\":1,"
                + "\"payload\":{"
                + "\"orderId\":\"33333333-3333-3333-3333-333333333333\","
                + "\"accountId\":4,\"symbol\":\"TCS.NS\",\"side\":\"BUY\",\"quantity\":10,"
                + "\"price\":3425.00,"
                + (executedPrice == null ? "\"executedPrice\":null," : "\"executedPrice\":" + executedPrice + ",")
                + "\"status\":\"" + status + "\","
                + (reason == null ? "\"reason\":null" : "\"reason\":\"" + reason + "\"")
                + "}}";
    }

    @Test
    void aFillBecomesAReadableFilledNotificationAndIsAcknowledged() {
        consumer.consume(event("ORDER_FILLED", "FILLED", null, "3425.00"), ack);

        verify(notifications).ingestTradeEvent(eq(EVENT), eq(4L), eq("ORDER_FILLED"),
                contains("Order filled: TCS.NS"), contains("filled at 3425.00"), eq(NotificationReference.order(UUID.fromString("33333333-3333-3333-3333-333333333333"), "TCS.NS")));
        verify(ack).acknowledge();
    }

    @Test
    void aRejectionCarriesItsReason() {
        consumer.consume(event("ORDER_REJECTED", "REJECTED", "INSUFFICIENT_FUNDS", null), ack);

        verify(notifications).ingestTradeEvent(eq(EVENT), eq(4L), eq("ORDER_REJECTED"),
                contains("Order rejected: TCS.NS"), contains("INSUFFICIENT_FUNDS"), any());
        verify(ack).acknowledge();
    }

    @Test
    void aCancellationIsReported() {
        consumer.consume(event("ORDER_CANCELLED", "CANCELLED", "CANCELLED_BY_CUSTOMER", null), ack);

        verify(notifications).ingestTradeEvent(eq(EVENT), eq(4L), eq("ORDER_CANCELLED"),
                contains("Order cancelled: TCS.NS"), contains("was cancelled"), any());
        verify(ack).acknowledge();
    }

    @Test
    void aMalformedMessageIsDroppedWithAnAcknowledgementAndNoIngest() {
        consumer.consume("this is not json", ack);

        verify(notifications, never()).ingestTradeEvent(any(), anyLong(), any(), any(), any(), any());
        verify(ack).acknowledge();
    }

    @Test
    void aDuplicateIsAcknowledgedWithoutError() {
        when(notifications.ingestTradeEvent(any(), anyLong(), any(), any(), any(), any())).thenReturn(false);

        consumer.consume(event("ORDER_FILLED", "FILLED", null, "3425.00"), ack);

        verify(ack).acknowledge();
    }

    @Test
    void aTransientFailureIsNotAcknowledgedSoTheMessageIsRedelivered() {
        when(notifications.ingestTradeEvent(any(), anyLong(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("database down"));

        assertThrows(RuntimeException.class, () -> consumer.consume(event("ORDER_FILLED", "FILLED", null, "3425.00"), ack));

        verify(ack, never()).acknowledge();
    }

    @Test
    void anOrderIdWrittenAsTheBlotterShowsItIsAcceptedToo() {
        consumer.consume(event("ORDER_REJECTED", "REJECTED", "INSUFFICIENT_FUNDS", null)
                .replace("\"orderId\":\"", "\"orderId\":\"ORD-"), ack);

        verify(notifications).ingestTradeEvent(eq(EVENT), eq(4L), eq("ORDER_REJECTED"), any(), any(),
                eq(NotificationReference.order(UUID.fromString("33333333-3333-3333-3333-333333333333"), "TCS.NS")));
        verify(ack).acknowledge();
    }

    @Test
    void anEventWithoutAnOrderIdStillBecomesANotification() {
        consumer.consume(event("ORDER_FILLED", "FILLED", null, "3425.00")
                .replace("\"orderId\":\"33333333-3333-3333-3333-333333333333\",", ""), ack);

        verify(notifications).ingestTradeEvent(eq(EVENT), eq(4L), eq("ORDER_FILLED"), any(), any(),
                eq(NotificationReference.order(null, "TCS.NS")));
        verify(ack).acknowledge();
    }
}
