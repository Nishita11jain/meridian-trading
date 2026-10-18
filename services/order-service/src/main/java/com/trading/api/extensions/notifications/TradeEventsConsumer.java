package com.trading.api.extensions.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Turns a trade-events message into a customer notification. Consumes the topic under this module's
 * own group ({@code notification-service}), so its offsets are independent of the executor's and of
 * the watchlist consumer's.
 *
 * <p>Acknowledgement: the offset is committed only after the notification is durably recorded (or the
 * message is a permanent failure that redelivery cannot fix). A transient failure is not acknowledged,
 * so the broker redelivers it; the ingest is idempotent on eventId, so a redelivery notifies no one
 * twice.
 *
 * <p>Gated by {@code trading.kafka.consumers.enabled} (default true) so a context that has no broker --
 * the persistence integration tests -- can switch the listener off.
 */
@Component
@ConditionalOnProperty(name = "trading.kafka.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class TradeEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(TradeEventsConsumer.class);
    private static final String TOPIC = "trade-events";

    private final ObjectMapper objectMapper;
    private final NotificationsService notifications;

    public TradeEventsConsumer(ObjectMapper objectMapper, NotificationsService notifications) {
        this.objectMapper = objectMapper;
        this.notifications = notifications;
    }

    @KafkaListener(topics = TOPIC, groupId = NotificationsService.CONSUMER_NAME,
            containerFactory = "extensionsKafkaListenerContainerFactory")
    public void consume(String message, Acknowledgment acknowledgment) {
        TradeEvent event;
        try {
            event = parse(message);
        } catch (Exception malformed) {
            // A message we can never make sense of: log and acknowledge, so one poison message does not
            // wedge the partition and stall notifications for every account on it.
            log.error("Dropping a malformed trade-events message: {}", malformed.getMessage());
            acknowledgment.acknowledge();
            return;
        }

        try {
            boolean created = notifications.ingestTradeEvent(
                    event.eventId(), event.accountId(), event.type(), event.subject(), event.body(),
                    NotificationReference.order(event.orderId(), event.symbol()));
            if (!created) {
                log.debug("trade-events {} already handled; acknowledging without notifying again", event.eventId());
            }
            acknowledgment.acknowledge();
        } catch (Exception transientFailure) {
            // Database down, lock contention, broker hiccup: do not acknowledge, so the message is
            // redelivered and tried again. Idempotency on eventId makes the retry safe.
            // The DefaultErrorHandler retries three times, one second apart; after that it moves
            // to the dead-letter topic (trade-events.DLT) for investigation.
            log.warn("Could not record notification for trade-events {}; retrying via DefaultErrorHandler: {}",
                    event.eventId(), transientFailure.getMessage());
            throw new RuntimeException("Transient notification ingestion failure", transientFailure);
        }
    }

    private TradeEvent parse(String message) throws Exception {
        JsonNode root = objectMapper.readTree(message);
        UUID eventId = UUID.fromString(root.path("eventId").asText());
        String eventType = root.path("eventType").asText();
        JsonNode payload = root.path("payload");
        long accountId = payload.path("accountId").asLong();
        String symbol = payload.path("symbol").asText();
        // The order the event is about, so the notification can link to it; absent ids are tolerated.
        String rawOrderId = payload.path("orderId").asText("").replaceFirst("^ORD-", "");
        UUID orderId = rawOrderId.isEmpty() ? null : UUID.fromString(rawOrderId);
        String side = payload.path("side").asText();
        long quantity = payload.path("quantity").asLong();
        String status = payload.path("status").asText();
        String reason = payload.hasNonNull("reason") ? payload.path("reason").asText() : null;
        // Format to two places so the message reads "at 3425.00", not "3425.0" (a JSON number drops the
        // trailing zero). Money on this platform is two decimal places.
        String executedPrice = payload.hasNonNull("executedPrice")
                ? new BigDecimal(payload.path("executedPrice").asText()).setScale(2, RoundingMode.HALF_UP).toPlainString()
                : null;

        if (eventId == null || accountId <= 0 || symbol.isEmpty()) {
            throw new IllegalArgumentException("missing eventId, accountId or symbol");
        }
        return new TradeEvent(eventId, eventType, accountId, orderId, symbol, side, quantity, status, reason, executedPrice);
    }

    /** A parsed trade-events message and the human-readable notification it becomes. No sensitive data. */
    private record TradeEvent(UUID eventId, String eventType, long accountId, UUID orderId, String symbol, String side,
                              long quantity, String status, String reason, String executedPrice) {

        String type() {
            return eventType;
        }

        String subject() {
            return switch (status) {
                case "FILLED" -> "Order filled: " + symbol;
                case "REJECTED" -> "Order rejected: " + symbol;
                case "CANCELLED" -> "Order cancelled: " + symbol;
                default -> "Order update: " + symbol;
            };
        }

        String body() {
            String base = "Your " + side + " order for " + quantity + " " + symbol;
            return switch (status) {
                case "FILLED" -> base + " was filled" + (executedPrice != null ? " at " + executedPrice : "") + ".";
                case "REJECTED" -> base + " was rejected" + (reason != null ? " (" + reason + ")" : "") + ".";
                case "CANCELLED" -> base + " was cancelled.";
                default -> base + " was updated.";
            };
        }
    }
}
