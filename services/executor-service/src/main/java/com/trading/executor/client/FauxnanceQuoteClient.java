package com.trading.executor.client;

import com.trading.executor.domain.Quote;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class FauxnanceQuoteClient implements QuoteClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_BATCH_SIZE = 25;

    private static final Logger log = LoggerFactory.getLogger(FauxnanceQuoteClient.class);

    private final RestClient restClient;
    private final String apiKey;
    private final FauxnanceQuotaTracker quotaTracker;

    @Autowired
    public FauxnanceQuoteClient(
            @Value("${fauxnance.base-url}") String baseUrl,
            @Value("${fauxnance.api-key}") String apiKey,
            FauxnanceQuotaTracker quotaTracker) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.quotaTracker = quotaTracker;
    }

    // Test-only constructor: lets FauxnanceQuoteClientTest inject a mocked
    // RestClient and a mocked/real FauxnanceQuotaTracker directly, bypassing
    // Spring's @Value resolution entirely. Package-private on purpose --
    // Spring must never see this as a second autowiring candidate.
    FauxnanceQuoteClient(
            RestClient restClient,
            String apiKey,
            FauxnanceQuotaTracker quotaTracker) {

        this.restClient = restClient;
        this.apiKey = apiKey;
        this.quotaTracker = quotaTracker;
    }

    @Override
    public Quote getQuote(String symbol) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // Every HTTP attempt spends one request of the daily quota, retries included.
            if (!quotaTracker.tryReserve(1)) {
                log.warn("Fauxnance daily quota exhausted, skipping call. remainingToday={}", quotaTracker.remainingToday());
                return null;
            }

            try {
                Map<?, ?> response = restClient.get()
                        .uri("/quotes/{symbol}", symbol)
                        .header("X-Api-Key", apiKey)
                        .retrieve()
                        .body(Map.class);

                return extractSingleQuote(response, symbol);

            } catch (RestClientException exception) {
                if (attempt == MAX_ATTEMPTS) return null;
                pauseBeforeRetry(attempt);
            }
        }
        return null;
    }

    @Override
    public Map<String, Quote> getBatchQuotes(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptyMap();
        }

        if (symbols.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("At most 25 symbols are allowed per batch call");
        }

        Map<String, Quote> parsed = new LinkedHashMap<>();
        String csvSymbols = symbols.stream().collect(Collectors.joining(","));

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // Every HTTP attempt spends one request of the daily quota, retries included.
            if (!quotaTracker.tryReserve(1)) {
                log.warn("Fauxnance daily quota exhausted, skipping call. remainingToday={}", quotaTracker.remainingToday());
                return parsed;
            }

            try {
                Map<?, ?> response = restClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/quotes").queryParam("symbols", csvSymbols).build())
                        .header("X-Api-Key", apiKey)
                        .retrieve()
                        .body(Map.class);

                parsed.putAll(extractBatchQuotes(response));
                return parsed;

            } catch (RestClientException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    return parsed;
                }
                pauseBeforeRetry(attempt);
            }
        }

        return parsed;
    }

    private static Quote extractSingleQuote(Map<?, ?> response, String defaultSymbol) {
        if (response == null) {
            return null;
        }

        Map<?, ?> data = asMap(response.get("data"));
        if (data == null) {
            return null;
        }

        // Supports both flat data payloads and nested { data: { quote: {...} } } payloads.
        Map<?, ?> quoteData = asMap(data.get("quote"));
        if (quoteData == null) {
            quoteData = data;
        }

        return buildQuote(quoteData, defaultSymbol, asBoolean(data.get("stale")));
    }

    private static Map<String, Quote> extractBatchQuotes(Map<?, ?> response) {
        if (response == null) {
            return Collections.emptyMap();
        }

        Map<?, ?> data = asMap(response.get("data"));
        if (data == null) {
            return Collections.emptyMap();
        }

        Object quotesObject = data.get("quotes");
        if (!(quotesObject instanceof List<?> quoteEntries)) {
            return Collections.emptyMap();
        }

        Map<String, Quote> result = new LinkedHashMap<>();
        for (Object entryObject : quoteEntries) {
            Map<?, ?> entry = asMap(entryObject);
            if (entry == null) {
                continue;
            }

            Map<?, ?> nestedQuote = asMap(entry.get("quote"));
            if (nestedQuote == null) {
                continue;
            }

            String fallbackSymbol = entry.get("symbol") == null ? null : String.valueOf(entry.get("symbol"));
            Quote quote = buildQuote(nestedQuote, fallbackSymbol, asBoolean(entry.get("stale")));
            if (quote != null && quote.symbol() != null) {
                result.put(quote.symbol(), quote);
            }
        }

        return result;
    }

    private static Quote buildQuote(Map<?, ?> quoteData, String fallbackSymbol, boolean staleFromEntry) {
        if (quoteData == null || quoteData.get("price") == null) {
            return null;
        }

        Object bid = quoteData.get("bid");
        Object ask = quoteData.get("ask");
        if (bid == null || ask == null) {
            return null;
        }

        String symbol = quoteData.get("symbol") == null
                ? fallbackSymbol
                : String.valueOf(quoteData.get("symbol"));

        return new Quote(
                symbol,
                new BigDecimal(String.valueOf(quoteData.get("price"))),
                new BigDecimal(String.valueOf(bid)),
                new BigDecimal(String.valueOf(ask)),
                parseInstant(quoteData.get("asOf")),
                quoteData.get("currency") == null ? null : String.valueOf(quoteData.get("currency")),
                optionalDecimal(quoteData.get("change")),
                optionalDecimal(quoteData.get("changePercent")),
                optionalDecimal(quoteData.get("previousClose")),
                marketState(quoteData.get("marketState")),
                staleFromEntry || asBoolean(quoteData.get("stale"))
        );
    }

    private static BigDecimal optionalDecimal(Object raw) {
        if (raw == null) {
            return null;
        }

        try {
            return new BigDecimal(String.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // The contract's marketState enum is lower case (open, closed, pre, post, unknown);
    // Fauxnance sends it upper case.
    private static String marketState(Object raw) {
        return raw == null ? "unknown" : String.valueOf(raw).toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean asBoolean(Object raw) {
        return raw instanceof Boolean flag && flag;
    }

    private static Instant parseInstant(Object asOfRaw) {
        if (asOfRaw == null) {
            return null;
        }

        try {
            return Instant.parse(String.valueOf(asOfRaw));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    private static void pauseBeforeRetry(int attempt) {
        try {
            Thread.sleep(50L * attempt);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}