package com.trading.api.extensions.advice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The one place {@code GET /candles/{symbol}} is called. Only ever reached from an explicit
 * customer "Analyse now" action past the cache's TTL (see {@link AdviceService}) -- never from a
 * scheduled job -- so this module's whole Fauxnance footprint is bounded by how many symbols
 * customers actually ask about, not by the size of the instrument catalogue.
 *
 * <p>The response shape ({@code data.candles[]} with date/open/high/low/close/volume) matches what
 * the analytics ETL layer's own Fauxnance client already reads from the same endpoint
 * (etl-layer/src/trading_analytics/client.py), so this is not a guess at the contract.
 */
@Component
public class FauxnanceCandleClient {

    private static final Logger log = LoggerFactory.getLogger(FauxnanceCandleClient.class);
    private static final int MAX_ATTEMPTS = 3;

    private final RestClient restClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    public FauxnanceCandleClient(
            @Value("${fauxnance.base-url}") String baseUrl,
            @Value("${fauxnance.api-key}") String apiKey,
            ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(5_000);
        requests.setReadTimeout(30_000);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requests).build();
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    /** The raw JSON body of {@code GET /candles/{symbol}}, or null if every attempt failed. */
    public String fetchRawCandles(String symbol) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                JsonNode response = restClient.get()
                        .uri("/candles/{symbol}", symbol)
                        .header("X-Api-Key", apiKey)
                        .retrieve()
                        .body(JsonNode.class);
                if (response == null) {
                    return null;
                }
                return objectMapper.writeValueAsString(response);
            } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException exception) {
                log.warn("Fauxnance candle fetch failed for {} (attempt {}/{}): {}",
                        symbol, attempt, MAX_ATTEMPTS, exception.getMessage());
                if (attempt == MAX_ATTEMPTS) {
                    return null;
                }
                pauseBeforeRetry(attempt);
            }
        }
        return null;
    }

    /** Parses a stored (or just-fetched) raw candle payload into closes ordered oldest to newest. */
    public List<Close> parseCloses(String rawJson) {
        return parseCandles(rawJson).stream().map(c -> new Close(c.date(), c.close())).toList();
    }

    /** Parses a stored (or just-fetched) raw candle payload into OHLC candles, oldest to newest. */
    public List<Candle> parseCandles(String rawJson) {
        List<Candle> parsed = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode candles = root.path("data").path("candles");
            if (!candles.isArray()) {
                return parsed;
            }
            for (JsonNode candle : candles) {
                JsonNode openNode = candle.path("open");
                JsonNode highNode = candle.path("high");
                JsonNode lowNode = candle.path("low");
                JsonNode closeNode = candle.path("close");
                JsonNode dateNode = candle.path("date");
                if (openNode.isMissingNode() || highNode.isMissingNode() || lowNode.isMissingNode()
                        || closeNode.isMissingNode() || dateNode.isMissingNode()) {
                    continue;
                }
                parsed.add(new Candle(
                        LocalDate.parse(dateNode.asText()),
                        new BigDecimal(openNode.asText()),
                        new BigDecimal(highNode.asText()),
                        new BigDecimal(lowNode.asText()),
                        new BigDecimal(closeNode.asText())));
            }
        } catch (Exception exception) {
            log.warn("Could not parse a cached/fetched candle payload: {}", exception.getMessage());
        }
        parsed.sort(java.util.Comparator.comparing(Candle::date));
        return parsed;
    }

    private static void pauseBeforeRetry(int attempt) {
        try {
            Thread.sleep(50L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public record Close(LocalDate date, BigDecimal close) {
    }

    public record Candle(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
    }
}
