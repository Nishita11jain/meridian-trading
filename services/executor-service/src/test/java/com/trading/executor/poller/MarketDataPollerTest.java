package com.trading.executor.poller;

import com.trading.executor.client.QuoteClient;
import com.trading.executor.domain.Quote;
import com.trading.executor.event.EventEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketDataPollerTest {

    @Mock
    private QuoteClient quoteClient;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private WatchedSymbolsMapper watchedSymbolsMapper;

    @Test
    void batchOfUpTo25SymbolsIsFetchedInOneRequest() {
        MarketDataPoller poller = new MarketDataPoller(quoteClient, kafkaTemplate, watchedSymbolsMapper, 60);

        List<String> symbols = IntStream.rangeClosed(1, 25)
                .mapToObj(i -> "SYM" + i)
                .toList();

        when(watchedSymbolsMapper.findDistinctWatchedSymbols()).thenReturn(symbols);
        when(quoteClient.getBatchQuotes(symbols)).thenReturn(Map.of());

        poller.pollMarketData();

        verify(quoteClient, times(1)).getBatchQuotes(symbols);
    }

    @Test
    void eachQuoteIsPublishedAsOwnMessageKeyedBySymbol() {
        MarketDataPoller poller = new MarketDataPoller(quoteClient, kafkaTemplate, watchedSymbolsMapper, 60);

        List<String> symbols = List.of("INFY.NS", "TCS.NS", "WIPRO.NS");

        Map<String, Quote> quotes = new LinkedHashMap<>();
        quotes.put("INFY.NS", new Quote("INFY.NS", new BigDecimal("100.00"), new BigDecimal("99.90"), new BigDecimal("100.10"), Instant.parse("2026-01-01T00:00:01Z")));
        quotes.put("TCS.NS", new Quote("TCS.NS", new BigDecimal("200.00"), new BigDecimal("199.90"), new BigDecimal("200.10"), Instant.parse("2026-01-01T00:00:02Z")));
        quotes.put("WIPRO.NS", new Quote("WIPRO.NS", new BigDecimal("300.00"), new BigDecimal("299.90"), new BigDecimal("300.10"), Instant.parse("2026-01-01T00:00:03Z")));

        when(watchedSymbolsMapper.findDistinctWatchedSymbols()).thenReturn(symbols);
        when(quoteClient.getBatchQuotes(symbols)).thenReturn(quotes);
        when(kafkaTemplate.send(any(String.class), any(String.class), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        poller.pollMarketData();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(3)).send(eq("market-data"), keyCaptor.capture(), any(EventEnvelope.class));

        List<String> sentKeys = keyCaptor.getAllValues();
        assertEquals(List.of("INFY.NS", "TCS.NS", "WIPRO.NS"), sentKeys);

        verify(kafkaTemplate, never()).send(eq("market-data"), eq("INFY.NS,TCS.NS,WIPRO.NS"), any(EventEnvelope.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void quoteMessageFollowsTheMarketDataContract() {
        MarketDataPoller poller = new MarketDataPoller(quoteClient, kafkaTemplate, watchedSymbolsMapper, 60);

        List<String> symbols = List.of("INFY.NS");
        Quote quote = new Quote(
                "INFY.NS", new BigDecimal("100.00"), new BigDecimal("99.90"), new BigDecimal("100.10"),
                Instant.parse("2026-01-01T00:00:01Z"), "INR", new BigDecimal("0.21"), new BigDecimal("0.09"),
                new BigDecimal("99.79"), "open", false);

        when(watchedSymbolsMapper.findDistinctWatchedSymbols()).thenReturn(symbols);
        when(quoteClient.getBatchQuotes(symbols)).thenReturn(Map.of("INFY.NS", quote));
        when(kafkaTemplate.send(any(String.class), any(String.class), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        poller.pollMarketData();

        ArgumentCaptor<EventEnvelope<MarketDataPoller.MarketDataPayload>> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(kafkaTemplate).send(eq("market-data"), eq("INFY.NS"), captor.capture());

        EventEnvelope<MarketDataPoller.MarketDataPayload> envelope = captor.getValue();
        assertEquals("QUOTE", envelope.eventType());
        assertEquals("market-poller", envelope.source());
        assertEquals(1, envelope.schemaVersion());
        assertEquals("INR", envelope.payload().currency());
        assertEquals("open", envelope.payload().marketState());
        assertEquals(Instant.parse("2026-01-01T00:00:01Z"), envelope.payload().quoteAsOf());
    }

    @Test
    void pollIsSkippedWhenItWouldEatIntoTheFillReserve() {
        com.trading.executor.client.FauxnanceQuotaTracker tracker = new com.trading.executor.client.FauxnanceQuotaTracker();
        tracker.tryReserve(1600); // 400 left, below the 500 kept back for fills
        MarketDataPoller poller = new MarketDataPoller(quoteClient, kafkaTemplate, watchedSymbolsMapper, 60, tracker);

        when(watchedSymbolsMapper.findDistinctWatchedSymbols()).thenReturn(List.of("INFY.NS"));

        poller.pollMarketData();

        verify(quoteClient, never()).getBatchQuotes(any());
    }

    @Test
    void intervalThatWouldLeaveNothingForFillsIsRejected() {
        // 44 s is 1,964 polls a day: under the 2,000 quota, but it leaves 36 requests for fills.
        MarketDataPoller poller = new MarketDataPoller(quoteClient, kafkaTemplate, watchedSymbolsMapper, 44);

        assertThrows(IllegalStateException.class, poller::validateIntervalFloor);
    }

    @Test
    void aQuoteTheBrokerDoesNotAcknowledgeIsLoggedAndDoesNotStopTheRestOfThePoll() {
        MarketDataPoller poller = new MarketDataPoller(quoteClient, kafkaTemplate, watchedSymbolsMapper, 60);
        List<String> symbols = List.of("INFY.NS", "TCS.NS");
        Map<String, Quote> quotes = new LinkedHashMap<>();
        quotes.put("INFY.NS", new Quote("INFY.NS", new BigDecimal("100.00"), new BigDecimal("99.90"), new BigDecimal("100.10")));
        quotes.put("TCS.NS", new Quote("TCS.NS", new BigDecimal("200.00"), new BigDecimal("199.90"), new BigDecimal("200.10")));
        when(watchedSymbolsMapper.findDistinctWatchedSymbols()).thenReturn(symbols);
        when(quoteClient.getBatchQuotes(symbols)).thenReturn(quotes);
        when(kafkaTemplate.send(eq("market-data"), eq("INFY.NS"), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new RuntimeException("broker down")));
        when(kafkaTemplate.send(eq("market-data"), eq("TCS.NS"), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        assertDoesNotThrow(poller::pollMarketData);

        verify(kafkaTemplate, times(2)).send(eq("market-data"), any(String.class), any());
    }

    @Test
    void configuredIntervalStaysInsideDailyQuotaForDeclaredSet() {
        MarketDataPoller poller = new MarketDataPoller(quoteClient, kafkaTemplate, watchedSymbolsMapper, 60);

        assertDoesNotThrow(poller::validateIntervalFloor);
    }

    @Test
    void intervalFloorIsEnforcedInCode() {
        MarketDataPoller poller = new MarketDataPoller(quoteClient, kafkaTemplate, watchedSymbolsMapper, 10);

        assertThrows(IllegalStateException.class, poller::validateIntervalFloor);
    }
}
