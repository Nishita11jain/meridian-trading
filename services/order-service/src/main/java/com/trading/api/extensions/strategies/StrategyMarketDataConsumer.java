package com.trading.api.extensions.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.api.extensions.ProcessedEventsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Consumes market-data quotes under its own group, {@code strategy-service}, so its offsets are
 * independent of the watchlist and notification consumers -- one falling behind or being
 * reprocessed never affects the others. Idempotent on eventId via the same shared
 * {@link ProcessedEventsMapper} ledger the other Sprint 10 consumers use, so an at-least-once
 * redelivery of the same quote cannot evaluate (and potentially fire) a strategy twice.
 */
@Component
@ConditionalOnProperty(name = "trading.kafka.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class StrategyMarketDataConsumer {

    static final String CONSUMER_NAME = "strategy-service";
    private static final Logger log = LoggerFactory.getLogger(StrategyMarketDataConsumer.class);
    private static final String TOPIC = "market-data";

    private final ObjectMapper objectMapper;
    private final ProcessedEventsMapper processedEventsMapper;
    private final StrategyExecutionService executionService;

    public StrategyMarketDataConsumer(ObjectMapper objectMapper, ProcessedEventsMapper processedEventsMapper,
                                       StrategyExecutionService executionService) {
        this.objectMapper = objectMapper;
        this.processedEventsMapper = processedEventsMapper;
        this.executionService = executionService;
    }

    @KafkaListener(topics = TOPIC, groupId = CONSUMER_NAME,
            containerFactory = "extensionsKafkaListenerContainerFactory")
    public void consume(String message, Acknowledgment acknowledgment) {
        UUID eventId;
        String symbol;
        BigDecimal price;
        BigDecimal bid;
        BigDecimal ask;
        Instant quoteAsOf;
        try {
            JsonNode root = objectMapper.readTree(message);
            eventId = UUID.fromString(root.path("eventId").asText());
            JsonNode payload = root.path("payload");
            symbol = payload.path("symbol").asText();
            price = payload.hasNonNull("price") ? new BigDecimal(payload.path("price").asText()) : null;
            bid = payload.hasNonNull("bid") ? new BigDecimal(payload.path("bid").asText()) : null;
            ask = payload.hasNonNull("ask") ? new BigDecimal(payload.path("ask").asText()) : null;
            if (symbol.isEmpty() || price == null || price.signum() <= 0) {
                throw new IllegalArgumentException("missing symbol or non-positive price");
            }
            quoteAsOf = payload.hasNonNull("quoteAsOf")
                    ? Instant.parse(payload.path("quoteAsOf").asText())
                    : Instant.now();
        } catch (Exception malformed) {
            log.error("Dropping a malformed market-data message: {}", malformed.getMessage());
            acknowledgment.acknowledge();
            return;
        }

        try {
            if (processedEventsMapper.claim(CONSUMER_NAME, eventId) == 1) {
                executionService.onQuote(symbol, price, bid, ask, quoteAsOf, eventId);
            }
            acknowledgment.acknowledge();
        } catch (Exception transientFailure) {
            log.warn("Could not evaluate strategies for {} on event {}; retrying via DefaultErrorHandler: {}",
                    symbol, eventId, transientFailure.getMessage());
            throw new RuntimeException("Transient strategy evaluation failure", transientFailure);
        }
    }
}
