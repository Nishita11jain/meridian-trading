package com.trading.api.service;

import com.trading.api.event.EventEnvelope;
import com.trading.api.event.OrderPlacedEvent;
import com.trading.api.event.TradeEventPayload;
import com.trading.domain.Money;
import com.trading.domain.Order;
import com.trading.domain.Position;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class KafkaOrderEventPublisher
        implements OrderEventPublisher {

    private static final String TOPIC = "orders";
    private static final String TRADE_EVENTS_TOPIC = "trade-events";
    private static final String SOURCE = "trade-api";
    private static final long PUBLISH_TIMEOUT_SECONDS = 5;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaOrderEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate) {

        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishOrderPlaced(Order order) {

        OrderPlacedEvent event =
                new OrderPlacedEvent(
                        UUID.randomUUID().toString(),
                        "ORDER_PLACED",
                        Instant.now(),
                        SOURCE,
                        1,
                        new OrderPlacedEvent.Payload(
                                order.getId(),
                                order.getAccountId(),
                                order.getSymbol(),
                                order.getSide(),
                                order.getQuantity(),
                                order.getLimitPrice()
                                        .asBigDecimal(),
                                order.getIdempotencyKey(),
                                order.getCreatedOn()
                        )
                );

        // Wait for the broker's acknowledgement so a failed publish is noticed
        // (and the order later replayed) instead of being lost silently.
        sendAndWait(
                TOPIC,
                String.valueOf(order.getAccountId()),
                event,
                "ORDER_PLACED",
                order.getId()
        );
    }

    @Override
    public void publishOrderCancelled(Order order, Position position) {

        Instant now = Instant.now();

        EventEnvelope<TradeEventPayload> event =
                new EventEnvelope<>(
                        UUID.randomUUID().toString(),
                        "ORDER_CANCELLED",
                        now,
                        SOURCE,
                        1,
                        new TradeEventPayload(
                                order.getId(),
                                order.getAccountId(),
                                order.getSymbol(),
                                order.getSide(),
                                order.getQuantity(),
                                order.getLimitPrice().asBigDecimal(),
                                null,
                                "CANCELLED",
                                "CANCELLED_BY_CUSTOMER",
                                Money.ZERO.asBigDecimal(),
                                position == null ? 0 : position.getQuantity(),
                                position == null
                                        ? Money.ZERO.asBigDecimal()
                                        : position.getAverageCost().asBigDecimal(),
                                now
                        )
                );

        // The cancel has already committed. Waiting tells the caller whether the event
        // reached the broker; the caller logs a failure (a cancelled order is not NEW, so
        // the reconciler will not replay it).
        sendAndWait(
                TRADE_EVENTS_TOPIC,
                String.valueOf(order.getAccountId()),
                event,
                "ORDER_CANCELLED",
                order.getId()
        );
    }

    @Override
    public void publishOrderRejected(UUID orderId, com.trading.domain.PlaceOrderRequest request,
                                     String reason, Position position) {

        Instant now = Instant.now();

        EventEnvelope<TradeEventPayload> event =
                new EventEnvelope<>(
                        UUID.randomUUID().toString(),
                        "ORDER_REJECTED",
                        now,
                        SOURCE,
                        1,
                        new TradeEventPayload(
                                orderId,
                                request.accountId(),
                                request.symbol(),
                                request.side(),
                                request.quantity(),
                                request.price(),
                                null,
                                "REJECTED",
                                reason,
                                Money.ZERO.asBigDecimal(),
                                position == null ? 0 : position.getQuantity(),
                                position == null
                                        ? Money.ZERO.asBigDecimal()
                                        : position.getAverageCost().asBigDecimal(),
                                now
                        )
                );

        // Nothing moved: no cash and no position changed, so cashDelta is zero and the position
        // figures are the account's position as it stands.
        sendAndWait(
                TRADE_EVENTS_TOPIC,
                String.valueOf(request.accountId()),
                event,
                "ORDER_REJECTED",
                orderId
        );
    }

    private void sendAndWait(
            String topic,
            String key,
            Object event,
            String eventType,
            UUID orderId) {

        try {
            kafkaTemplate.send(topic, key, event)
                    .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OrderPublishException(
                    "Interrupted while publishing " + eventType + " for order " + orderId, exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new OrderPublishException(
                    "Could not publish " + eventType + " for order " + orderId, exception);
        }
    }
}
