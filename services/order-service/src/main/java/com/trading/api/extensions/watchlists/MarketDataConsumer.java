package com.trading.api.extensions.watchlists;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Consumes market-data quotes under the group {@code watchlist-service}, maintains the latest-price
 * read model and the tick history, and fires any price alert the quote has crossed. Its offsets are
 * independent of the notification consumer and the executor because the group id is its own.
 *
 * <p>Every quote for every symbol passes through here, so parsing is defensive: a message that cannot
 * be a valid quote (no symbol, non-positive price) is dropped with an acknowledgement rather than
 * redelivered forever, and the market_state is normalised to the values the schema allows so an
 * unexpected one cannot wedge the partition.
 */
@Component
@ConditionalOnProperty(name = "trading.kafka.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class MarketDataConsumer {

    private static final Logger log = LoggerFactory.getLogger(MarketDataConsumer.class);
    private static final String TOPIC = "market-data";
    private static final Set<String> MARKET_STATES = Set.of("open", "closed", "pre", "post");

    private final ObjectMapper objectMapper;
    private final MarketDataService marketDataService;

    public MarketDataConsumer(ObjectMapper objectMapper, MarketDataService marketDataService) {
        this.objectMapper = objectMapper;
        this.marketDataService = marketDataService;
    }

    @KafkaListener(topics = TOPIC, groupId = MarketDataService.CONSUMER_NAME,
            containerFactory = "extensionsKafkaListenerContainerFactory")
    public void consume(String message, Acknowledgment acknowledgment) {
        UUID eventId;
        MarketDataService.MarketQuote quote;
        try {
            JsonNode root = objectMapper.readTree(message);
            eventId = UUID.fromString(root.path("eventId").asText());
            quote = parseQuote(root);
        } catch (Exception malformed) {
            log.error("Dropping a malformed market-data message: {}", malformed.getMessage());
            acknowledgment.acknowledge();
            return;
        }

        try {
            marketDataService.ingestQuote(eventId, quote);
            acknowledgment.acknowledge();
        } catch (Exception transientFailure) {
            // Database down, lock contention, broker hiccup: do not acknowledge, so the message is
            // redelivered and tried again. Idempotency on eventId makes the retry safe.
            // The DefaultErrorHandler will retry three times, one second apart; after that it moves
            // to the dead-letter topic (market-data.DLT) for investigation.
            log.warn("Could not process market-data {} for {}; retrying via DefaultErrorHandler: {}",
                    eventId, quote.symbol(), transientFailure.getMessage());
            throw new RuntimeException("Transient market data ingestion failure", transientFailure);
        }
    }

    private MarketDataService.MarketQuote parseQuote(JsonNode root) {
        JsonNode payload = root.path("payload");
        String symbol = payload.path("symbol").asText();
        BigDecimal price = decimal(payload, "price");
        if (symbol.isEmpty() || price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("missing symbol or non-positive price");
        }

        String currency = payload.path("currency").asText("").trim().toUpperCase();
        if (!currency.matches("^[A-Z]{3}$")) {
            currency = "USD";
        }
        String marketState = payload.path("marketState").asText("unknown").trim().toLowerCase();
        if (!MARKET_STATES.contains(marketState)) {
            marketState = "unknown";
        }
        Instant quoteAsOf = payload.hasNonNull("quoteAsOf")
                ? Instant.parse(payload.path("quoteAsOf").asText())
                : Instant.parse(root.path("eventTime").asText(Instant.now().toString()));

        return new MarketDataService.MarketQuote(symbol, price,
                decimal(payload, "bid"), decimal(payload, "ask"), currency,
                decimal(payload, "change"), decimal(payload, "changePercent"), decimal(payload, "previousClose"),
                marketState, payload.path("stale").asBoolean(false), quoteAsOf);
    }

    private static BigDecimal decimal(JsonNode payload, String field) {
        return payload.hasNonNull(field) ? new BigDecimal(payload.path(field).asText()) : null;
    }
}
