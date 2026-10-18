package com.trading.api.extensions.watchlists;

import com.trading.api.extensions.ProcessedEventsMapper;
import com.trading.api.extensions.advice.CandleCacheMapper;
import com.trading.api.extensions.advice.CandleCacheRow;
import com.trading.api.extensions.advice.FauxnanceCandleClient;
import com.trading.api.extensions.notifications.NotificationDelivery;
import com.trading.domain.exception.InstrumentNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Quote ingest, idempotency, and the heart of the module: deciding when an alert has been crossed. */
class MarketDataServiceTest {

    private static final UUID EVENT = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private MarketDataMapper marketDataMapper;
    private AlertsMapper alertsMapper;
    private PriceAlertStateMapper stateMapper;
    private ProcessedEventsMapper processedEvents;
    private NotificationDelivery delivery;
    private CandleCacheMapper candleCacheMapper;
    private FauxnanceCandleClient candleClient;
    private MarketDataService service;

    @BeforeEach
    void setUp() {
        marketDataMapper = mock(MarketDataMapper.class);
        alertsMapper = mock(AlertsMapper.class);
        stateMapper = mock(PriceAlertStateMapper.class);
        processedEvents = mock(ProcessedEventsMapper.class);
        delivery = mock(NotificationDelivery.class);
        candleCacheMapper = mock(CandleCacheMapper.class);
        candleClient = mock(FauxnanceCandleClient.class);
        when(processedEvents.claim(eq("watchlist-service"), any())).thenReturn(1);
        when(alertsMapper.markTriggered(anyLong(), any())).thenReturn(1);
        service = new MarketDataService(marketDataMapper, alertsMapper, stateMapper, processedEvents, delivery,
                mock(com.trading.api.mapper.InstrumentMapper.class),
            org.mockito.Mockito.mock(com.trading.api.service.TradingHours.class),
            candleCacheMapper, candleClient, 24);
    }

    private static MarketDataService.MarketQuote quote(String price) {
        return new MarketDataService.MarketQuote("TCS.NS", new BigDecimal(price), null, null, "INR",
                null, null, null, "open", false, Instant.parse("2026-10-01T09:00:00Z"));
    }

    private static AlertRow alert(String direction, String target, String lastObserved) {
        AlertRow row = new AlertRow();
        row.setId(7L);
        row.setAccountId(4L);
        row.setSymbol("TCS.NS");
        row.setDirection(direction);
        row.setTargetPrice(new BigDecimal(target));
        row.setLastObservedPrice(lastObserved == null ? null : new BigDecimal(lastObserved));
        return row;
    }

    @Test
    void aQuoteIsStoredAndHistoryAppended() {
        when(alertsMapper.findActiveBySymbol("TCS.NS")).thenReturn(List.of());

        boolean created = service.ingestQuote(EVENT, quote("3500.00"));

        assertTrue(created);
        verify(marketDataMapper).upsertMarketPrice(eq("TCS.NS"), eq(new BigDecimal("3500.00")), any(), any(),
                eq("INR"), any(), any(), any(), eq("open"), eq(false), any());
        verify(marketDataMapper).insertPriceHistory(eq("TCS.NS"), eq(new BigDecimal("3500.00")), any(), any(), any());
    }

    @Test
    void aRedeliveredQuoteIsNotProcessedAgain() {
        when(processedEvents.claim(eq("watchlist-service"), any())).thenReturn(0);

        assertFalse(service.ingestQuote(EVENT, quote("3500.00")));
        verify(marketDataMapper, never()).upsertMarketPrice(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBool(), any());
    }

    @Test
    void anAboveAlertFiresWhenThePriceRisesThroughTheTarget() {
        when(alertsMapper.findActiveBySymbol("TCS.NS")).thenReturn(List.of(alert("ABOVE", "3480", "3450")));

        service.ingestQuote(EVENT, quote("3500.00"));

        verify(alertsMapper).markTriggered(7L, new BigDecimal("3500.00"));
        verify(delivery).deliver(eq(4L), any(), eq("PRICE_ALERT_TRIGGERED"), any(), any(),
                eq(com.trading.api.extensions.notifications.NotificationReference.alert(7L, "TCS.NS")));
        verify(stateMapper).upsertLastObserved(7L, new BigDecimal("3500.00"));
    }

    @Test
    void aBelowAlertFiresWhenThePriceFallsThroughTheTarget() {
        when(alertsMapper.findActiveBySymbol("TCS.NS")).thenReturn(List.of(alert("BELOW", "3480", "3500")));

        service.ingestQuote(EVENT, quote("3450.00"));

        verify(alertsMapper).markTriggered(7L, new BigDecimal("3450.00"));
        verify(delivery).deliver(eq(4L), any(), eq("PRICE_ALERT_TRIGGERED"), any(), any(),
                eq(com.trading.api.extensions.notifications.NotificationReference.alert(7L, "TCS.NS")));
    }

    @Test
    void anAboveAlertDoesNotFireAgainWhenThePriceWasAlreadyPastTheTarget() {
        when(alertsMapper.findActiveBySymbol("TCS.NS")).thenReturn(List.of(alert("ABOVE", "3480", "3490")));

        service.ingestQuote(EVENT, quote("3500.00"));

        verify(alertsMapper, never()).markTriggered(anyLong(), any());
        verify(delivery, never()).deliver(anyLong(), any(), any(), any(), any(), any());
        verify(stateMapper).upsertLastObserved(7L, new BigDecimal("3500.00")); // still records the observation
    }

    @Test
    void anAlertDoesNotFireWhenTheTargetIsNotMet() {
        when(alertsMapper.findActiveBySymbol("TCS.NS")).thenReturn(List.of(alert("ABOVE", "3480", "3450")));

        service.ingestQuote(EVENT, quote("3470.00"));

        verify(alertsMapper, never()).markTriggered(anyLong(), any());
        verify(delivery, never()).deliver(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void aFirstObservationAlreadyPastTheTargetFires() {
        when(alertsMapper.findActiveBySymbol("TCS.NS")).thenReturn(List.of(alert("ABOVE", "3480", null)));

        service.ingestQuote(EVENT, quote("3500.00"));

        verify(alertsMapper).markTriggered(7L, new BigDecimal("3500.00"));
    }

    @Test
    void getQuoteThrowsWhenTheSymbolHasNoStoredPrice() {
        when(marketDataMapper.findBySymbol("ZZZ")).thenReturn(null);

        assertThrows(InstrumentNotFoundException.class, () -> service.getQuote("ZZZ"));
    }

    @Test
    void rangeHistoryUsesCachedCandlesWhenAvailable() {
        CandleCacheRow cache = new CandleCacheRow();
        cache.setCandlesJson("cached-json");
        cache.setExpiresAt(Instant.now().plusSeconds(3600));
        when(candleCacheMapper.findBySymbol("TCS.NS")).thenReturn(cache);
        when(candleClient.parseCandles("cached-json")).thenReturn(List.of(
            new FauxnanceCandleClient.Candle(java.time.LocalDate.parse("2026-09-30"), new BigDecimal("3350.00"), new BigDecimal("3420.00"), new BigDecimal("3330.00"), new BigDecimal("3400.00")),
            new FauxnanceCandleClient.Candle(java.time.LocalDate.parse("2026-10-01"), new BigDecimal("3410.00"), new BigDecimal("3510.00"), new BigDecimal("3390.00"), new BigDecimal("3500.00"))));

        List<MarketQuoteResponse> history = service.getHistory("TCS.NS", "5D");

        assertEquals(2, history.size());
        assertEquals(0, new BigDecimal("3500.00").compareTo(history.get(0).lastPrice())); // newest first
        assertEquals(0, new BigDecimal("3410.00").compareTo(history.get(0).openPrice()));
        assertEquals(0, new BigDecimal("3510.00").compareTo(history.get(0).highPrice()));
        assertEquals(0, new BigDecimal("3390.00").compareTo(history.get(0).lowPrice()));
        org.mockito.Mockito.verify(candleClient, never()).fetchRawCandles(any());
    }

    @Test
    void rangeHistoryRefreshesFromFauxnanceWhenCacheIsMissing() {
        when(candleCacheMapper.findBySymbol("TCS.NS")).thenReturn(null);
        when(candleClient.fetchRawCandles("TCS.NS")).thenReturn("fresh-json");
        when(candleClient.parseCandles("fresh-json")).thenReturn(List.of(
            new FauxnanceCandleClient.Candle(java.time.LocalDate.parse("2026-04-01"), new BigDecimal("2950.00"), new BigDecimal("3050.00"), new BigDecimal("2920.00"), new BigDecimal("3000.00")),
            new FauxnanceCandleClient.Candle(java.time.LocalDate.parse("2026-10-01"), new BigDecimal("3450.00"), new BigDecimal("3520.00"), new BigDecimal("3430.00"), new BigDecimal("3500.00"))));

        service.getHistory("TCS.NS", "6M");

        verify(candleCacheMapper).upsert(eq("TCS.NS"), eq("1d"), eq(null), eq("fresh-json"), any(), any());
    }

    private static boolean anyBool() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}
