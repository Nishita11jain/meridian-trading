package com.trading.executor.poller;

import com.trading.executor.client.QuoteClient;
import com.trading.executor.domain.Quote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FxRatePollerTest {

    private final QuoteClient quoteClient = mock(QuoteClient.class);
    private final FxRateMapper mapper = mock(FxRateMapper.class);
    private final FxRatePoller poller = new FxRatePoller(quoteClient, mapper);

    private static Quote quote(String symbol, String price) {
        return new Quote(symbol, new BigDecimal(price), null, null, Instant.parse("2026-10-08T00:00:00Z"));
    }

    @Test
    void bothPairsAreFetchedInOneCallAndStored() {
        when(quoteClient.getBatchQuotes(List.of("FX:USDINR", "FX:INRUSD"))).thenReturn(Map.of(
                "FX:USDINR", quote("FX:USDINR", "83.4125"),
                "FX:INRUSD", quote("FX:INRUSD", "0.011989")));

        int stored = poller.poll();

        assertEquals(2, stored);
        verify(mapper).upsert(eq("USDINR"), eq(new BigDecimal("83.412500")), any());
        verify(mapper).upsert(eq("INRUSD"), eq(new BigDecimal("0.011989")), any());
    }

    @Test
    void aFailedFetchStoresNothingAndDoesNotThrow() {
        when(quoteClient.getBatchQuotes(any())).thenThrow(new IllegalStateException("fauxnance down"));

        assertEquals(0, poller.poll());

        verify(mapper, never()).upsert(any(), any(), any());
    }

    @Test
    void aPairWithNoUsableRateIsSkipped() {
        when(quoteClient.getBatchQuotes(any())).thenReturn(Map.of("FX:USDINR", quote("FX:USDINR", "83.41")));

        assertEquals(1, poller.poll());

        verify(mapper, never()).upsert(eq("INRUSD"), any(), any());
    }

    @Test
    void startupPollsOnlyWhenTodaysRatesAreMissing() {
        when(mapper.countToday()).thenReturn(2);

        poller.pollIfMissingAtStartup();

        verify(quoteClient, never()).getBatchQuotes(any());
    }

    @Test
    void startupPollsWhenARateIsMissing() {
        when(mapper.countToday()).thenReturn(1);
        when(quoteClient.getBatchQuotes(any())).thenReturn(Map.of());

        poller.pollIfMissingAtStartup();

        verify(quoteClient).getBatchQuotes(List.of("FX:USDINR", "FX:INRUSD"));
    }
}
