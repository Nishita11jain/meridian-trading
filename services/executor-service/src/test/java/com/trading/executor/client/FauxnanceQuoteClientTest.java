package com.trading.executor.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FauxnanceQuoteClientTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void getBatchQuotesParsesConfirmedNestedResponseShape() throws Exception {
        RestClient restClient = mock(RestClient.class);
      RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
      RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        String confirmedJson = """
                {
                  "data": {
                    "quotes": [
                      {
                        "symbol": "INFY",
                        "source": "cache",
                        "stale": false,
                        "quote": {
                          "symbol": "INFY",
                          "price": 11.32,
                          "bid": 11.31,
                          "ask": 11.33,
                          "spreadBps": 17.67,
                          "currency": "USD",
                          "change": -0.09,
                          "changePercent": -0.79,
                          "previousClose": 11.41,
                          "asOf": "2026-09-17T00:00:00Z",
                          "marketState": "OPEN"
                        }
                      },
                      {
                        "symbol": "TCS",
                        "source": "cache",
                        "stale": false,
                        "quote": {
                          "symbol": "TCS",
                          "price": 99.25,
                          "bid": 99.20,
                          "ask": 99.30,
                          "spreadBps": 10.07,
                          "currency": "USD",
                          "change": 0.15,
                          "changePercent": 0.15,
                          "previousClose": 99.10,
                          "asOf": "2026-09-17T00:00:01Z",
                          "marketState": "OPEN"
                        }
                      }
                    ]
                  }
                }
                """;

        Map<?, ?> responseMap = new ObjectMapper().readValue(confirmedJson, Map.class);

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(any(Function.class))).thenReturn(headersSpec);
        when(headersSpec.header(eq("X-Api-Key"), eq("test-api-key"))).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(responseMap);

        FauxnanceQuotaTracker tracker = new FauxnanceQuotaTracker(
                Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC)
        );
        FauxnanceQuoteClient client = new FauxnanceQuoteClient(restClient, "test-api-key", tracker);

        Map<String, com.trading.executor.domain.Quote> quotes = client.getBatchQuotes(List.of("INFY", "TCS"));

        assertEquals(2, quotes.size());

        com.trading.executor.domain.Quote infy = quotes.get("INFY");
        assertNotNull(infy);
        assertEquals(0, infy.price().compareTo(new BigDecimal("11.32")));
        assertEquals(0, infy.bid().compareTo(new BigDecimal("11.31")));
        assertEquals(0, infy.ask().compareTo(new BigDecimal("11.33")));

        com.trading.executor.domain.Quote tcs = quotes.get("TCS");
        assertNotNull(tcs);
        assertEquals(0, tcs.price().compareTo(new BigDecimal("99.25")));
        assertEquals(0, tcs.bid().compareTo(new BigDecimal("99.20")));
        assertEquals(0, tcs.ask().compareTo(new BigDecimal("99.30")));
    }
}
