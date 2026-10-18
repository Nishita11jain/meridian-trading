package com.trading.executor.poller;

import com.trading.executor.client.FauxnanceQuotaTracker;
import com.trading.executor.client.QuoteClient;
import com.trading.executor.domain.Quote;
import com.trading.executor.event.EventEnvelope;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class MarketDataPoller {

    private static final Logger log = LoggerFactory.getLogger(MarketDataPoller.class);

    private static final int MAX_BATCH_SIZE = 25;
    private static final int DAILY_QUOTA = 2000;

    /**
     * Requests of the daily quota kept back for pricing fills, which share the
     * key with this poller. The poller may spend DAILY_QUOTA - FILL_RESERVE.
     */
    static final int FILL_RESERVE = 500;

    private static final long SECONDS_PER_DAY = 86400L;
    private static final String MARKET_DATA_TOPIC = "market-data";
    private static final long PUBLISH_TIMEOUT_SECONDS = 5;

    private final QuoteClient quoteClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final WatchedSymbolsMapper watchedSymbolsMapper;
    private final long pollIntervalSeconds;
    private final FauxnanceQuotaTracker quotaTracker;

    @Autowired
    public MarketDataPoller(
            QuoteClient quoteClient,
            KafkaTemplate<String, Object> kafkaTemplate,
            WatchedSymbolsMapper watchedSymbolsMapper,
            @Value("${poll.interval-seconds:300}") long pollIntervalSeconds,
            FauxnanceQuotaTracker quotaTracker) {

        this.quoteClient = quoteClient;
        this.kafkaTemplate = kafkaTemplate;
        this.watchedSymbolsMapper = watchedSymbolsMapper;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.quotaTracker = quotaTracker;
    }

    MarketDataPoller(
            QuoteClient quoteClient,
            KafkaTemplate<String, Object> kafkaTemplate,
            WatchedSymbolsMapper watchedSymbolsMapper,
            long pollIntervalSeconds) {

        this(quoteClient, kafkaTemplate, watchedSymbolsMapper, pollIntervalSeconds, null);
    }

    /**
     * Rejects an interval that would spend more than the poller's share of the
     * daily quota for one batch of up to 25 symbols. The fills reserve is left
     * alone, so pricing orders never starves. More than 25 held symbols cost
     * one extra request per chunk each poll; pollMarketData checks the
     * remaining quota for that case on every poll.
     */
    @PostConstruct
    void validateIntervalFloor() {
        if (pollIntervalSeconds <= 0) {
            throw new IllegalStateException("poll.interval-seconds must be > 0");
        }

        long pollerBudget = DAILY_QUOTA - FILL_RESERVE;
        long requestsPerDay = (SECONDS_PER_DAY + pollIntervalSeconds - 1) / pollIntervalSeconds;
        if (requestsPerDay > pollerBudget) {
            throw new IllegalStateException(
                    "poll.interval-seconds leaves no room for fills: requestsPerDay="
                            + requestsPerDay + ", pollerBudget=" + pollerBudget
                            + " (dailyQuota=" + DAILY_QUOTA + " minus fillReserve=" + FILL_RESERVE + ")"
            );
        }
    }

    @Scheduled(fixedDelayString = "${poll.interval-seconds:300}000")
    public void pollMarketData() {
        try {
            List<String> symbols = watchedSymbolsMapper.findDistinctWatchedSymbols();
            if (symbols == null || symbols.isEmpty()) {
                return;
            }

            List<List<String>> chunks = partition(symbols, MAX_BATCH_SIZE);

            if (quotaTracker != null
                    && quotaTracker.remainingToday() - chunks.size() < FILL_RESERVE) {
                log.warn(
                        "Skipping market data poll to protect the fill reserve: remainingToday={}, chunks={}, reserve={}",
                        quotaTracker.remainingToday(), chunks.size(), FILL_RESERVE
                );
                return;
            }

            for (List<String> chunk : chunks) {
                Map<String, Quote> quotes = quoteClient.getBatchQuotes(chunk);
                if (quotes == null || quotes.isEmpty()) {
                    continue;
                }

                List<PendingSend> pending = new ArrayList<>();

                for (Map.Entry<String, Quote> entry : quotes.entrySet()) {
                    String symbol = entry.getKey();
                    Quote quote = entry.getValue();

                    EventEnvelope<MarketDataPayload> envelope = new EventEnvelope<>(
                            UUID.randomUUID().toString(),
                            "QUOTE",
                            Instant.now(),
                            "market-poller",
                            1,
                            new MarketDataPayload(
                                    symbol,
                                    quote.price(),
                                    quote.bid(),
                                    quote.ask(),
                                    quote.currency(),
                                    quote.change(),
                                    quote.changePercent(),
                                    quote.previousClose(),
                                    quote.marketState(),
                                    quote.stale(),
                                    quote.asOf()
                            )
                    );

                    pending.add(new PendingSend(symbol, kafkaTemplate.send(MARKET_DATA_TOPIC, symbol, envelope)));
                }

                awaitAcknowledgements(pending);
            }
        } catch (Exception exception) {
            log.error("Market data poll failed", exception);
        }
    }

    /**
     * Sends for a batch go out together, then each is waited on, so one slow acknowledgement
     * does not serialise the rest. A quote that is not acknowledged is logged and dropped: the
     * next poll publishes a fresher one, so there is nothing to retry.
     */
    private void awaitAcknowledgements(List<PendingSend> pending) {
        for (PendingSend send : pending) {
            try {
                send.result().get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for the quote for {} to be acknowledged", send.symbol());
                return;
            } catch (ExecutionException | TimeoutException exception) {
                log.warn("Quote for {} was not acknowledged by the broker: {}", send.symbol(), exception.getMessage());
            }
        }
    }

    private record PendingSend(String symbol, CompletableFuture<?> result) {
    }

    private static List<List<String>> partition(List<String> symbols, int chunkSize) {
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptyList();
        }

        List<List<String>> chunks = new ArrayList<>();
        for (int index = 0; index < symbols.size(); index += chunkSize) {
            int end = Math.min(index + chunkSize, symbols.size());
            chunks.add(symbols.subList(index, end));
        }
        return chunks;
    }

    /** market-data QUOTE payload as fixed by contracts/event-schemas/kafka-topics.md. */
    public record MarketDataPayload(
            String symbol,
            BigDecimal price,
            BigDecimal bid,
            BigDecimal ask,
            String currency,
            BigDecimal change,
            BigDecimal changePercent,
            BigDecimal previousClose,
            String marketState,
            boolean stale,
            Instant quoteAsOf
    ) {
    }
}
